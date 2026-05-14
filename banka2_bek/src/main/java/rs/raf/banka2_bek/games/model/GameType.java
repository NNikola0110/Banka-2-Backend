package rs.raf.banka2_bek.games.model;

/**
 * Tipovi igara u Sobi za cekanje (feature dodat 14.05.2026 vece-5).
 * <ul>
 *   <li>{@link #DINO} — Chrome T-Rex stil endless jumper sa banker karakterom</li>
 *   <li>{@link #SOLITAIRE} — Klondike Solitaire (score = moves, NIZI je bolji)</li>
 *   <li>{@link #CHESS} — sah sa chess.js engine-om</li>
 *   <li>{@link #BANKA2_RUSH} — Subway Surfers stil endless runner</li>
 * </ul>
 */
public enum GameType {
    DINO,
    SOLITAIRE,
    CHESS,
    BANKA2_RUSH
}
