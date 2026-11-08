package com.netpulse.device;

import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;

final class DeviceSpecifications {
    private DeviceSpecifications() { }

    static Specification<Device> hasStatus(DeviceStatus s) {
        return s == null ? null : (r, q, cb) -> cb.equal(r.get("status"), s);
    }

    static Specification<Device> hasType(DeviceType t) {
        return t == null ? null : (r, q, cb) -> cb.equal(r.get("type"), t);
    }

    static Specification<Device> hasTag(String tag) {
        if (tag == null || tag.isBlank()) return null;
        String value = tag.trim().toLowerCase(Locale.ROOT);
        return (r, q, cb) -> cb.equal(r.join("tags", JoinType.INNER), value);
    }

    /** Case-insensitive contains on name/host/location. LIKE wildcards in user input are escaped. */
    static Specification<Device> search(String text) {
        if (text == null || text.isBlank()) return null;
        String escaped = text.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String like = "%" + escaped + "%";
        return (r, q, cb) -> cb.or(
                cb.like(cb.lower(r.get("name")), like, '\\'),
                cb.like(cb.lower(r.get("host")), like, '\\'),
                cb.like(cb.lower(cb.coalesce(r.get("location"), "")), like, '\\'));
    }
}
