package app.walldock.esphome;

import java.util.ArrayList;
import java.util.List;

/**
 * Action proposée à Home Assistant (« service utilisateur » ESPHome).
 * Elle apparaît dans HA sous le nom esphome.&lt;appareil&gt;_&lt;nom&gt;, avec ses paramètres.
 */
public final class UserService {

    public static final int ARG_BOOL = 0;
    public static final int ARG_INT = 1;
    public static final int ARG_FLOAT = 2;
    public static final int ARG_STRING = 3;

    public final String name;
    public final int key;
    final List<String> argNames = new ArrayList<>();
    final List<Integer> argTypes = new ArrayList<>();

    public UserService(String name) {
        this.name = name;
        this.key = Entity.fnv1("service:" + name);
    }

    public UserService arg(String argName, int type) {
        argNames.add(argName);
        argTypes.add(type);
        return this;
    }
}
