package com.netpulse.user;

/** Phase 1 only creates ADMIN. OPERATOR/VIEWER are wired into the schema now and enforced in Phase 3 (RBAC). */
public enum Role { ADMIN, OPERATOR, VIEWER }
