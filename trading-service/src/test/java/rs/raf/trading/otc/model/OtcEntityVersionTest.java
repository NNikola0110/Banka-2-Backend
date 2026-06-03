package rs.raf.trading.otc.model;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import static org.assertj.core.api.Assertions.assertThat;

class OtcEntityVersionTest {
    @Test
    void otcContractHasVersionField() throws Exception {
        Field f = OtcContract.class.getDeclaredField("version");
        assertThat(f.getType()).isEqualTo(Long.class);
        assertThat(f.isAnnotationPresent(jakarta.persistence.Version.class)).isTrue();
    }
    @Test
    void otcOfferHasVersionField() throws Exception {
        Field f = OtcOffer.class.getDeclaredField("version");
        assertThat(f.isAnnotationPresent(jakarta.persistence.Version.class)).isTrue();
    }
    @Test
    void portfolioHasVersionField() throws Exception {
        Field f = rs.raf.trading.portfolio.model.Portfolio.class.getDeclaredField("version");
        assertThat(f.isAnnotationPresent(jakarta.persistence.Version.class)).isTrue();
    }
}
