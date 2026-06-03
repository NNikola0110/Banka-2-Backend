package rs.raf.banka2_bek.account.util;

import rs.raf.banka2_bek.account.model.AccountSubtype;
import rs.raf.banka2_bek.account.model.AccountType;

import java.security.SecureRandom;

public final class AccountNumberUtils {

    /**
     * P2-config-2 (R2 1416): JEDINSTVEN izvor maticnog broja banke. Routing
     * broj banke (prve 3 cifre svakog naseg racuna) = prve 3 cifre maticnog
     * broja. Ranije je "222" bio dva puta hardkodiran (ovde {@code BANK_CODE}
     * i {@code interbank.my-routing-number=222} u application.properties) bez
     * ikakve veze → drift rizik. Sad {@link #BANK_CODE} izvodimo iz ovog
     * konstantnog maticnog broja (mora se poklapati sa property
     * {@code bank.registration-number=22200022}), a {@code interbank.my-routing-number}
     * je dokumentovan kao derivat istog izvora (vidi application.properties komentar).
     */
    public static final String BANK_REGISTRATION_NUMBER = "22200022";

    /** Routing broj / bank code = prve 3 cifre maticnog broja (npr. 222). */
    static final String BANK_CODE = BANK_REGISTRATION_NUMBER.substring(0, 3);
    private static final String BRANCH_CODE = "0001";
    /**
     * BE-ACC-02 (defense-in-depth): {@link java.util.Random} (LCG) je
     * predvidljiv iz nekoliko output-a — attacker bi mogao da generise par
     * racuna kroz javni register flow pa da predvidi sledecu sekvencu (broj
     * racuna je polu-tajan identifikator za payment/transfer/OTC operacije).
     * {@link SecureRandom} koristi OS CSPRNG (Windows {@code CryptGenRandom},
     * Linux {@code /dev/urandom}) — non-deterministic.
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private AccountNumberUtils() {}

    public static String generate(AccountType type, AccountSubtype subtype, boolean isBusiness) {
        String typeDigits = determineTypeDigits(type, subtype, isBusiness);
        while (true) {
            String randomPart = String.format("%09d", SECURE_RANDOM.nextInt(1_000_000_000));
            String candidate = BANK_CODE + BRANCH_CODE + randomPart + typeDigits;
            if (isValidMod11(candidate)) {
                return candidate;
            }
        }
    }

    private static String determineTypeDigits(AccountType type, AccountSubtype subtype, boolean isBusiness) {
        if (type == AccountType.FOREIGN) {
            return isBusiness ? "22" : "21";
        }
        if (type == AccountType.BUSINESS || isBusiness) {
            return "12";
        }
        if (type == AccountType.CHECKING && subtype != null) {
            // R1-624: SALARY/STANDARD se eksplicitno mapiraju na genericki "10"
            // (tekuci racun bez posebne kategorije) — pre toga su tiho padali na
            // `default` istog ishoda, sto je skrivalo da su namerno genericki.
            // Exhaustive switch (bez `default`) hvata buduce dodate subtype-ove
            // u kompajlu umesto da ih nemo svrsta u "10".
            return switch (subtype) {
                case PERSONAL -> "11";
                case SAVINGS -> "13";
                case PENSION -> "14";
                case YOUTH -> "15";
                case STUDENT -> "16";
                case UNEMPLOYED -> "17";
                case SALARY, STANDARD -> "10";
            };
        }
        return "10";
    }

    private static boolean isValidMod11(String accountNumber) {
        int sum = 0;
        for (char c : accountNumber.toCharArray()) {
            sum += Character.getNumericValue(c);
        }
        return sum % 11 == 0;
    }
}
