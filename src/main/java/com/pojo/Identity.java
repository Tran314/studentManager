package com.pojo;

import java.io.Serializable;

/** Session holds no entity or password material. */
public record Identity(long id, String username, String role, Integer studentSno, int authVersion) implements Serializable {
    public boolean isAdmin() { return "ADMIN".equals(role); }
    /** Jakarta EL 6 resolves record properties through accessor-named methods. */
    public boolean admin() { return isAdmin(); }
    public String getUsername() { return username; }
    public String getRole() { return role; }
    public static Identity of(Account account) {
        return new Identity(account.getId(), account.getUsername(), account.getRole(),
            account.getStudent() == null ? null : account.getStudent().getSno(), account.getAuthVersion());
    }
}
