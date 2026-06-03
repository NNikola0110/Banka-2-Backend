package rs.raf.notification.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.MessageConverter;
import rs.raf.banka2.contracts.NotificationKind;
import rs.raf.banka2.contracts.NotificationMessage;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * TEST-notif-contracts-1 (OT-1597 / OT-1819b) — conversion-error → DLQ (NE tight-loop).
 *
 * <p><b>Problem koji se pinuje:</b> kad stigne malformirana JSON poruka ili
 * poruka sa nepoznatim {@link NotificationKind} stringom u {@code kind}, greska
 * se desava U {@code JacksonJsonMessageConverter}-u (container nivo) PRE nego sto
 * {@link NotificationConsumer#handle} uopste bude pozvan. Posledica: consumer-ove
 * brizljivo napisane try/catch grane (transient→requeue / poison→DLQ) NIKAD ne
 * vide conversion-error. Sigurnost protiv beskonacnog requeue tight-loop-a tada
 * pociva ISKLJUCIVO na container-factory konfiguraciji:
 * {@code defaultRequeueRejected=false} + DLX wiring na email queue-u (RabbitConfig).
 *
 * <p>Ovaj test je CHARAKTERIZACIONI: dokazuje (1) da converter ZAISTA baca
 * {@link MessageConversionException} na oba poison oblika, i (2) da je container
 * factory podesen tako da odbacena poruka ide na DLX umesto da se vrti u petlji.
 * Ako bi neko obrnuo {@code defaultRequeueRejected} na {@code true}, conversion
 * poison bi se zauvek re-deliveruje-ovao (CPU/queue hot-loop) — test pukne.
 */
class NotificationConversionErrorTest {

    private final RabbitConfig config = new RabbitConfig();
    private final MessageConverter converter = config.jsonMessageConverter();

    /** Validna poruka pretvorena u AMQP Message (postavlja __TypeId__ header za fromMessage). */
    private Message validWireMessage() {
        NotificationMessage msg = new NotificationMessage(NotificationKind.OTP,
                Map.of("email", "a@b.rs", "code", "654321", "expiryMinutes", "5"));
        return converter.toMessage(msg, new MessageProperties());
    }

    @Test
    void validMessage_roundTripsThroughConverter() {
        // Sanity: ispravna poruka se uspesno deserijalizuje (kontrolna grupa).
        Object back = converter.fromMessage(validWireMessage());
        assertThat(back).isInstanceOf(NotificationMessage.class);
        NotificationMessage nm = (NotificationMessage) back;
        assertThat(nm.kind()).isEqualTo(NotificationKind.OTP);
        assertThat(nm.data()).containsEntry("code", "654321");
    }

    @Test
    void unknownKindString_throwsConversionExceptionAtConverterLayer() {
        // Poruka sa kind="NOPE_NOT_A_KIND" — Jackson ne moze da mapira enum →
        // MessageConversionException PRE listener-a (consumer catch je zaobidjen).
        Message valid = validWireMessage();
        String tampered = new String(valid.getBody()).replace("\"OTP\"", "\"NOPE_NOT_A_KIND\"");
        Message poison = new Message(tampered.getBytes(), valid.getMessageProperties());

        assertThatThrownBy(() -> converter.fromMessage(poison))
                .isInstanceOf(MessageConversionException.class);
    }

    @Test
    void malformedJsonBody_throwsConversionExceptionAtConverterLayer() {
        Message valid = validWireMessage();
        Message poison = new Message("{ this is : not valid json".getBytes(),
                valid.getMessageProperties());

        assertThatThrownBy(() -> converter.fromMessage(poison))
                .isInstanceOf(MessageConversionException.class);
    }

    @Test
    void containerFactory_rejectsWithoutRequeue_soConversionPoisonGoesToDlqNotHotLoop() {
        // KLJUCNA INVARIJANTA: posto conversion-error zaobilazi consumer, jedina
        // odbrana od tight-loop-a je defaultRequeueRejected=false (odbacena
        // poruka → DLX umesto requeue). Pinujemo da je factory tako podesen.
        ConnectionFactory cf = mock(ConnectionFactory.class);
        SimpleRabbitListenerContainerFactory factory =
                config.rabbitListenerContainerFactory(cf, converter);

        assertThat(factory).extracting("defaultRequeueRejected").isEqualTo(Boolean.FALSE);
        // I email queue mora imati DLX argumente da odbacena poruka stvarno
        // stigne u DLQ (a ne da se izgubi) — komplement gornje invarijante.
        assertThat(config.emailQueue().getArguments())
                .containsEntry("x-dead-letter-exchange", RabbitConfig.DLX_EXCHANGE);
    }
}
