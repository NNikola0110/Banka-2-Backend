package rs.raf.banka2_bek;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.retry.annotation.EnableRetry;

// R5/R6 config-drift fix: @EnableScheduling je premesten na zasebnu
// rs.raf.banka2_bek.config.SchedulingConfig klasu gejtovanu property-jem
// banka2.scheduling.enabled (default true, OFF u test profilu) — simetricno
// sa trading-service trading.scheduling.enabled. Tako banka-core scheduleri
// (interbank retry/reconciliation, savings, loan, otp, agent-action, ...)
// ne okidaju u @SpringBootTest kontekstu sa mock klijentima (flaky-test gard).
@SpringBootApplication
@EnableCaching
@EnableRetry
public class Banka2BekApplication {

	public static void main(String[] args) {
		SpringApplication.run(Banka2BekApplication.class, args);
	}

}
