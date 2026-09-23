package com.publicis.nosql.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class TenantFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            String t = req.getHeader("X-Tenant-Id");
            TenantContext.set(t == null || t.isBlank() ? "tenantA" : t);
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();      // never leak tenant state across pooled threads
        }
    }
}
