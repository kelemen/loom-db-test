[#include "/common/drop-table-safe.sql.ftl"];
[#include "/common/create-table.sql.ftl"];
[#include "/common/insert.sql.ftl"];

DROP PROCEDURE IF EXISTS SLEEP;

CREATE PROCEDURE SLEEP
(
  @delaystr nvarchar(12)
)
AS BEGIN
  WAITFOR DELAY @delaystr;
  SELECT 1;
END;
