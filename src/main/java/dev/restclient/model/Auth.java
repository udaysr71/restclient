package dev.restclient.model;

import java.util.Objects;

/** Request authentication helper settings. */
public class Auth {
    public enum Type { NONE, BASIC, BEARER }

    public Type type = Type.NONE;
    public String username = "";
    public String password = "";
    public String token = "";

    public Auth copy() {
        Auth a = new Auth();
        a.type = type;
        a.username = username;
        a.password = password;
        a.token = token;
        return a;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Auth a)) return false;
        return type == a.type && Objects.equals(username, a.username)
                && Objects.equals(password, a.password) && Objects.equals(token, a.token);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, username, password, token);
    }
}
