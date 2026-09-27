package dev.hamzarezgui.stockledger.db;

import dev.hamzarezgui.stockledger.domain.Qty;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.math.BigDecimal;

/**
 * Maps {@link Qty} to the {@code numeric} columns the ledger uses.
 *
 * <p>{@code autoApply = true} so every {@code Qty} field maps without a
 * per-field {@code @Convert}. Forgetting that annotation would make Hibernate
 * treat {@code Qty} as an unknown basic type and fail at startup, which is a
 * survivable but pointless failure mode.
 *
 * <p>Both directions go through {@code BigDecimal} because that is what JDBC
 * exchanges with {@code numeric}. No {@code double} appears anywhere in the
 * path, so the exactness {@link Qty} guarantees in memory survives the round
 * trip to the database — see {@code docs/adr/0002-exact-quantities.md}.
 *
 * <p>Reading is strict on purpose: if a stored value somehow carries more than
 * three decimals, {@link Qty#of(BigDecimal)} throws rather than quietly
 * rounding it. A database that disagrees with the domain should be loud.
 */
@Converter(autoApply = true)
public class QtyConverter implements AttributeConverter<Qty, BigDecimal> {

    @Override
    public BigDecimal convertToDatabaseColumn(Qty qty) {
        return qty == null ? null : qty.toBigDecimal();
    }

    @Override
    public Qty convertToEntityAttribute(BigDecimal value) {
        return value == null ? null : Qty.of(value);
    }
}
