package app.walldock.esphome;

import java.nio.charset.StandardCharsets;

/** Une entité exposée à Home Assistant (capteur, interrupteur, bouton…). */
public final class Entity {

    public enum Kind { SENSOR, BINARY_SENSOR, SWITCH, NUMBER, BUTTON, TEXT_SENSOR }

    /** Catégories d'entité ESPHome. */
    public static final int CATEGORY_NONE = 0;
    public static final int CATEGORY_CONFIG = 1;
    public static final int CATEGORY_DIAGNOSTIC = 2;

    /** state_class des capteurs ESPHome. */
    public static final int STATE_CLASS_NONE = 0;
    public static final int STATE_CLASS_MEASUREMENT = 1;

    /** Mode d'affichage des entités number. */
    public static final int NUMBER_MODE_AUTO = 0;
    public static final int NUMBER_MODE_BOX = 1;
    public static final int NUMBER_MODE_SLIDER = 2;

    public final Kind kind;
    public final String objectId;
    public final String name;
    public final int key;

    public String icon = "";
    public String unit = "";
    public String deviceClass = "";
    public int entityCategory = CATEGORY_NONE;
    public int stateClass = STATE_CLASS_NONE;
    public int accuracyDecimals = 0;
    public float min = 0f;
    public float max = 100f;
    public float step = 1f;
    public int numberMode = NUMBER_MODE_SLIDER;

    /** État courant : Float (sensor, number), Boolean (binary_sensor, switch), String (text_sensor). */
    volatile Object state;

    public Entity(Kind kind, String objectId, String name) {
        this.kind = kind;
        this.objectId = objectId;
        this.name = name;
        this.key = fnv1(objectId);
    }

    public Entity icon(String value) { icon = value; return this; }
    public Entity unit(String value) { unit = value; return this; }
    public Entity deviceClass(String value) { deviceClass = value; return this; }
    public Entity category(int value) { entityCategory = value; return this; }
    public Entity measurement() { stateClass = STATE_CLASS_MEASUREMENT; return this; }
    public Entity range(float min, float max, float step) { this.min = min; this.max = max; this.step = step; return this; }

    public Object getState() { return state; }

    /** Même hachage que ESPHome pour les clés d'entité (FNV-1, 32 bits). */
    static int fnv1(String text) {
        int hash = 0x811C9DC5;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            hash *= 0x01000193;
            hash ^= (b & 0xFF);
        }
        return hash;
    }
}
