package com.herasgarden.gardentrade.api;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public interface BusinessDirectory {
    List<Vacancy> vacancies() throws SQLException;
    boolean hire(UUID positionId, UUID employeeId, String employeeName) throws SQLException;
    boolean vacate(UUID positionId, UUID employeeId) throws SQLException;

    record Vacancy(UUID businessId, String businessName, UUID workplaceId, String workplaceName,
                   UUID territoryClaimId, UUID positionId, String positionTitle, long wage) {}
}
