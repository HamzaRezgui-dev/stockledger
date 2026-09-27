package dev.hamzarezgui.stockledger;

import org.springframework.boot.SpringApplication;

public class TestStockledgerApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(StockledgerApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
