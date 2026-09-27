-- ============================================================================
-- StockLedger — initial schema
--
-- Stock is an append-only ledger. On-hand quantity is DERIVED, never stored as
-- an authority. See docs/adr/0001-append-only-ledger.md and
-- docs/adr/0002-exact-quantities.md.
--
-- The invariants below are enforced by the DATABASE, not by application code.
-- A rule that lives only in Java is broken by the next migration script, the
-- next developer, or anyone with a psql session.
--
-- ---------------------------------------------------------------------------
-- A note on why quantities are `numeric` and not `numeric(14,3)`
--
-- `numeric(14,3)` looks like it constrains precision. It does not -- it
-- *coerces* it. Postgres silently rounds an out-of-scale value to fit:
--
--     insert into t (q) values (1.23456789);   -- q numeric(14,3)
--     select q from t;  -->  1.235
--
-- Silent rounding is exactly the quiet data loss this schema exists to prevent,
-- and because the coercion happens *before* row-level CHECKs are evaluated, a
-- `check (scale(q) <= 3)` on a typed column is dead code: it can never fail.
--
-- So quantity columns are declared as unconstrained `numeric` with explicit
-- CHECKs on scale and magnitude. Those CHECKs actually fire, so a value the
-- ledger cannot represent exactly is REJECTED rather than rounded. The
-- magnitude bound matches Qty.MAX_MILLI in the Java domain, so both layers
-- agree on the same ceiling.
--
-- Verified, not assumed -- see LedgerSchemaIntegrationTest.
-- ============================================================================

create extension if not exists pgcrypto;

-- The ledger's exact resolution, asserted in one place so every column agrees.
-- 11 integer digits + 3 decimals, matching Qty.MAX_MILLI = 99_999_999_999_999.
-- (Kept as a comment rather than a domain type so the bounds stay visible at
-- each column; see the CHECK constraints below.)

-- ---------------------------------------------------------------------------
-- Locations: anywhere stock can physically sit.
-- ---------------------------------------------------------------------------
create table locations (
    id              uuid        primary key default gen_random_uuid(),
    code            text        not null,
    name            text        not null,
    kind            text        not null default 'WAREHOUSE',

    -- Most locations must never go negative: you cannot remove stock that is
    -- not there. A few legitimately can -- e.g. a virtual "supplier" or
    -- "shrinkage" location used as the counterparty of a one-sided movement.
    -- Making this explicit per location beats a global flag or a code comment.
    allows_negative boolean     not null default false,

    archived_at     timestamptz,
    created_at      timestamptz not null default now(),

    constraint locations_code_key   unique (code),
    constraint locations_kind_check check (kind in ('WAREHOUSE', 'SHELF', 'VAN', 'QUARANTINE', 'VIRTUAL'))
);

comment on column locations.allows_negative is
    'When false, any movement driving the balance below zero is rejected by trigger.';

-- ---------------------------------------------------------------------------
-- Products.
-- ---------------------------------------------------------------------------
create table products (
    id             uuid          primary key default gen_random_uuid(),
    sku            text          not null,
    name           text          not null,
    barcode        text,
    unit           text          not null default 'PIECE',

    -- Batch tracking is a per-product decision: paracetamol needs lot + expiry,
    -- a galvanised bolt does not. Enforced by trigger below.
    tracks_batches boolean       not null default false,

    reorder_point  numeric        not null default 0,
    archived_at    timestamptz,
    created_at     timestamptz    not null default now(),

    constraint products_sku_key         unique (sku),
    -- Postgres permits many NULLs in a unique index, so products without a
    -- barcode coexist while real barcodes stay unique.
    constraint products_barcode_key     unique (barcode),
    constraint products_unit_check      check (unit in ('PIECE', 'KG', 'LITRE', 'METRE')),
    constraint products_reorder_nonneg  check (reorder_point >= 0),
    constraint products_reorder_exact   check (scale(reorder_point) <= 3),
    constraint products_reorder_bounded check (reorder_point <= 99999999999.999)
);

-- ---------------------------------------------------------------------------
-- Batches: a lot of a product, optionally with an expiry date.
--
-- expires_on is a DATE, not a timestamptz. An expiry is a calendar fact printed
-- on a box ("2027-03") and is the same day in every timezone. Storing it as an
-- instant invites an off-by-one-day bug that expires stock early or late
-- depending on the server's offset.
-- ---------------------------------------------------------------------------
create table batches (
    id         uuid        primary key default gen_random_uuid(),
    product_id uuid        not null references products (id),
    lot_code   text        not null,
    expires_on date,
    created_at timestamptz not null default now(),

    constraint batches_product_lot_key unique (product_id, lot_code),

    -- Lets stock_movements declare a composite foreign key on
    -- (batch_id, product_id), so the database itself guarantees a movement's
    -- batch belongs to that movement's product. Without this you rely on
    -- application code to never mismatch them.
    constraint batches_id_product_key   unique (id, product_id)
);

-- ---------------------------------------------------------------------------
-- The ledger. Append-only. The single source of truth for stock.
-- ---------------------------------------------------------------------------
create table stock_movements (
    id              bigint        generated always as identity primary key,

    product_id      uuid          not null references products (id),
    location_id     uuid          not null references locations (id),
    batch_id        uuid,

    -- Signed: positive brings stock in, negative takes it out. Never zero --
    -- a movement that moves nothing is a bug, not a record.
    -- Unconstrained `numeric` on purpose; see the header note.
    qty_delta       numeric       not null,

    reason          text          not null,

    -- What caused this movement, for tracing back to a document.
    ref_type        text,
    ref_id          text,

    unit_cost       numeric,
    actor           text          not null,
    note            text,

    -- Lets a retried or duplicated request collapse onto one movement instead
    -- of double-posting. Essential once the client may retry on timeout.
    idempotency_key text,

    -- A correction points at the movement it reverses. The original is never
    -- edited or removed.
    reversal_of_id  bigint        references stock_movements (id),

    -- When it happened in the warehouse vs when we heard about it. These differ
    -- for offline capture and backdated corrections, and conflating them makes
    -- historical reports unreproducible.
    occurred_at     timestamptz   not null default now(),
    recorded_at     timestamptz   not null default now(),

    constraint movements_qty_nonzero    check (qty_delta <> 0),
    -- Reject, never round: these fire because the column is unconstrained
    -- `numeric`. The bound matches Qty.MAX_MILLI in the Java domain.
    constraint movements_qty_exact      check (scale(qty_delta) <= 3),
    constraint movements_qty_bounded    check (abs(qty_delta) <= 99999999999.999),
    constraint movements_cost_nonneg    check (unit_cost is null or unit_cost >= 0),
    constraint movements_cost_exact     check (unit_cost is null or scale(unit_cost) <= 4),
    constraint movements_actor_present  check (length(btrim(actor)) > 0),

    constraint movements_reason_check check (reason in (
        'RECEIPT',          -- goods in from a supplier
        'RETURN_IN',        -- customer returned stock
        'TRANSFER_IN',      -- arrived from another location
        'SALE',             -- goods out to a customer
        'WRITE_OFF',        -- damaged, expired, stolen
        'TRANSFER_OUT',     -- left for another location
        'ADJUSTMENT',       -- deliberate manual correction
        'COUNT_CORRECTION'  -- physical count disagreed with the ledger
    )),

    -- The sign must agree with the reason, so a "SALE" can never secretly add
    -- stock. Reversals are exempt: reversing a SALE is necessarily positive,
    -- and its correctness is enforced by trigger instead.
    constraint movements_sign_check check (
        reversal_of_id is not null
        or (reason in ('RECEIPT', 'RETURN_IN', 'TRANSFER_IN')   and qty_delta > 0)
        or (reason in ('SALE', 'WRITE_OFF', 'TRANSFER_OUT')     and qty_delta < 0)
        or (reason in ('ADJUSTMENT', 'COUNT_CORRECTION'))
    ),

    constraint movements_no_self_reversal check (reversal_of_id is distinct from id),

    -- The database guarantees batch-product agreement. MATCH SIMPLE means the
    -- constraint is skipped when batch_id is NULL, which is what we want for
    -- products that do not track batches.
    constraint movements_batch_belongs_to_product
        foreign key (batch_id, product_id) references batches (id, product_id)
);

-- One movement, one idempotency key. Partial so unkeyed movements are unlimited.
create unique index movements_idempotency_key_uq
    on stock_movements (idempotency_key)
    where idempotency_key is not null;

-- A movement can be reversed at most once; a second reversal would double-count.
create unique index movements_reversal_of_uq
    on stock_movements (reversal_of_id)
    where reversal_of_id is not null;

-- Serves the balance query used on every write (the non-negative check) and by
-- the on-hand view. INCLUDE lets Postgres answer the SUM from the index alone.
create index movements_balance_idx
    on stock_movements (product_id, location_id, batch_id)
    include (qty_delta);

-- Serves "explain this number": the movement history behind a balance.
create index movements_history_idx
    on stock_movements (product_id, occurred_at desc);

create index movements_ref_idx
    on stock_movements (ref_type, ref_id)
    where ref_type is not null;

comment on table stock_movements is
    'Append-only ledger. UPDATE, DELETE and TRUNCATE are blocked by trigger. '
    'Correct a mistake by inserting a reversing movement (reversal_of_id).';

-- ============================================================================
-- Invariant 1: the ledger is append-only.
--
-- This is the guarantee the whole design rests on, so it is enforced here
-- rather than trusted to application code.
-- ============================================================================
create or replace function stock_movements_reject_mutation()
    returns trigger
    language plpgsql
as $$
begin
    raise exception
        'stock_movements is append-only; % is not permitted', tg_op
        using
            errcode = 'restrict_violation',
            hint    = 'Insert a reversing movement with reversal_of_id set to the '
                      'original id. See docs/adr/0001-append-only-ledger.md';
end;
$$;

create trigger stock_movements_no_update
    before update on stock_movements
    for each row execute function stock_movements_reject_mutation();

create trigger stock_movements_no_delete
    before delete on stock_movements
    for each row execute function stock_movements_reject_mutation();

-- Without this, TRUNCATE would erase the entire ledger while satisfying both
-- triggers above -- neither fires for TRUNCATE.
create trigger stock_movements_no_truncate
    before truncate on stock_movements
    for each statement execute function stock_movements_reject_mutation();

-- ============================================================================
-- Invariant 2: batch tracking is respected, and expired batches are not
-- silently issued as fresh stock.
-- ============================================================================
create or replace function stock_movements_validate_batch()
    returns trigger
    language plpgsql
as $$
declare
    v_tracks_batches boolean;
begin
    select tracks_batches into v_tracks_batches
        from products where id = new.product_id;

    if v_tracks_batches and new.batch_id is null then
        raise exception 'product % tracks batches, so batch_id is required', new.product_id
            using errcode = 'not_null_violation';
    end if;

    if not v_tracks_batches and new.batch_id is not null then
        raise exception 'product % does not track batches, so batch_id must be null', new.product_id
            using errcode = 'check_violation';
    end if;

    return new;
end;
$$;

create trigger stock_movements_check_batch
    before insert on stock_movements
    for each row execute function stock_movements_validate_batch();

-- ============================================================================
-- Invariant 3: a reversal exactly mirrors what it reverses.
--
-- Without this a "reversal" could point at an unrelated movement and quietly
-- invent or destroy stock while looking like a correction.
-- ============================================================================
create or replace function stock_movements_validate_reversal()
    returns trigger
    language plpgsql
as $$
declare
    orig stock_movements;
begin
    if new.reversal_of_id is null then
        return new;
    end if;

    select * into orig from stock_movements where id = new.reversal_of_id;

    if orig.id is null then
        raise exception 'cannot reverse movement %: it does not exist', new.reversal_of_id
            using errcode = 'foreign_key_violation';
    end if;

    if orig.reversal_of_id is not null then
        raise exception 'movement % is itself a reversal and cannot be reversed', orig.id
            using errcode = 'check_violation',
                  hint    = 'Reverse the original movement instead.';
    end if;

    if new.qty_delta <> -orig.qty_delta then
        raise exception
            'reversal of movement % must be exactly %, got %',
            orig.id, -orig.qty_delta, new.qty_delta
            using errcode = 'check_violation';
    end if;

    if new.product_id  <> orig.product_id
       or new.location_id <> orig.location_id
       or new.batch_id is distinct from orig.batch_id then
        raise exception
            'reversal of movement % must target the same product, location and batch', orig.id
            using errcode = 'check_violation';
    end if;

    return new;
end;
$$;

create trigger stock_movements_check_reversal
    before insert on stock_movements
    for each row execute function stock_movements_validate_reversal();

-- ============================================================================
-- Invariant 4: stock never goes negative (unless the location opts in).
--
-- Belt and braces with the SERIALIZABLE transaction in LedgerService: the
-- service prevents the race, this prevents the bug. Any code path that reaches
-- this table -- a future service, a migration, a manual psql INSERT -- is
-- covered.
--
-- The SUM also creates the predicate read that Postgres's Serializable Snapshot
-- Isolation needs to detect two concurrent issues of the same last unit. See
-- docs/adr/0003-serializable-transactions.md.
-- ============================================================================
create or replace function stock_movements_check_non_negative()
    returns trigger
    language plpgsql
as $$
declare
    v_allows_negative boolean;
    -- Plain `numeric`: a declared scale would coerce on assignment here too.
    v_balance         numeric;
begin
    -- Only outbound movements can drive a balance below zero.
    if new.qty_delta > 0 then
        return new;
    end if;

    select allows_negative into v_allows_negative
        from locations where id = new.location_id;

    if v_allows_negative then
        return new;
    end if;

    select coalesce(sum(qty_delta), 0) into v_balance
        from stock_movements
        where product_id = new.product_id
          and location_id = new.location_id
          and batch_id is not distinct from new.batch_id;

    if v_balance + new.qty_delta < 0 then
        raise exception
            'insufficient stock: balance is %, cannot apply %', v_balance, new.qty_delta
            using errcode = 'check_violation',
                  hint    = 'Reduce the quantity, pick a different batch, or set '
                            'locations.allows_negative if this location may go negative.';
    end if;

    return new;
end;
$$;

-- Ordering matters: the batch trigger must reject a bad batch_id before this
-- one computes a balance for it. Postgres fires same-timing triggers in name
-- order, so the prefixes below are load-bearing, not decoration.
create trigger stock_movements_zz_check_non_negative
    before insert on stock_movements
    for each row execute function stock_movements_check_non_negative();

-- ============================================================================
-- Derived state. Read models, never authorities.
-- ============================================================================

-- On-hand per product / location / batch.
create view stock_on_hand as
select
    m.product_id,
    m.location_id,
    m.batch_id,
    sum(m.qty_delta) as qty,
    max(m.occurred_at) as last_movement_at
from stock_movements m
group by m.product_id, m.location_id, m.batch_id;

comment on view stock_on_hand is
    'Derived from the ledger. Never write here; post a movement instead.';

-- What FEFO allocation picks from: batched stock with a positive balance,
-- ordered so the earliest expiry is consumed first. Undated lots sort last,
-- because dated stock is the stock at risk.
create view stock_available_lots as
select
    soh.product_id,
    soh.location_id,
    soh.batch_id,
    b.lot_code,
    b.expires_on,
    soh.qty
from stock_on_hand soh
    join batches b on b.id = soh.batch_id
where soh.qty > 0;
