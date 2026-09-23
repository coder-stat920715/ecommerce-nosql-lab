package com.publicis.nosql.tenant;

/** Per-request tenant holder (populated from the X-Tenant-Id header). */
public final class TenantContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private TenantContext() {}
    public static void set(String t) { CURRENT.set(t); }
    public static String get() { String t = CURRENT.get(); return t == null ? "tenantA" : t; }
    public static void clear() { CURRENT.remove(); }
}
