package com.herasgarden.gardentrade.api;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public interface BusinessDirectory {
    List<Vacancy> vacancies() throws SQLException;
    boolean hire(UUID positionId, UUID employeeId, String employeeName) throws SQLException;
    boolean vacate(UUID positionId, UUID employeeId) throws SQLException;
    java.util.Optional<Assignment> assignment(UUID employeeId) throws SQLException;
    boolean workplaceOpen(UUID workplaceId) throws SQLException;

    record Assignment(
            UUID businessId,
            String businessName,
            UUID workplaceId,
            String workplaceName,
            UUID territoryClaimId,
            UUID positionId,
            String positionTitle,
            long wage,
            String audience,
            int shiftStartMinute,
            int shiftEndMinute,
            UUID workWorldId,
            Integer workX,
            Integer workY,
            Integer workZ
    ) {}

    record Vacancy(
            UUID businessId,
            String businessName,
            UUID workplaceId,
            String workplaceName,
            UUID territoryClaimId,
            UUID positionId,
            String positionTitle,
            long wage,
            String audience,
            int shiftStartMinute,
            int shiftEndMinute,
            UUID workWorldId,
            Integer workX,
            Integer workY,
            Integer workZ
    ) {
        public boolean acceptsSociety() {
            return audience == null || audience.equalsIgnoreCase("ANY") || audience.equalsIgnoreCase("SOCIETY");
        }
    }
}
