package rs.raf.banka2_bek.interbank.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamWriteFeature;

/**
 * T2-C fix (Tim 1 cross-bank Stage A, 2026-05-20): Jackson 3 customizer koji
 * enable-uje {@link StreamWriteFeature#WRITE_BIGDECIMAL_AS_PLAIN}.
 *
 * <p>Razlog: u Jackson 2.x ova osobina je bila u
 * {@code SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN} i moglo se podesiti
 * kroz {@code spring.jackson.serialization.write-bigdecimal-as-plain=true}.
 * U Jackson 3.x premestena je u {@code StreamWriteFeature}, koji Spring Boot 4
 * vise ne expose-uje kroz {@code spring.jackson.*} namespace, pa je potreban
 * eksplicitan customizer bean.
 *
 * <p>Spec §2.5 zahteva da BigDecimal vrednosti u JSON payload-u budu zapisane
 * "kao obican broj bez exponent notacije". Bez ovog customizer-a, Jackson 3
 * default-no enkodira BigDecimal 100 u scientific notation ({@code "1E+2"}),
 * sto partner banka koja parse-uje {@code Posting.amount} kao Double moze
 * izgubiti preciznost. Sinhrono sa Tim 1 fix-om u njihovom
 * {@code InterbankJacksonConfig.writeBigDecimalAsPlainCustomizer}.
 */
@Configuration
public class InterbankJacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer writeBigDecimalAsPlainCustomizer() {
        return builder -> builder.configure(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN, true);
    }
}
