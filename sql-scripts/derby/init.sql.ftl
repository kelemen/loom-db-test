[#include "/common/drop-table-safe.sql.ftl"];
[#include "/common/create-table.sql.ftl"];
[#include "/common/insert.sql.ftl"];

[#if db.hasFunction("SLEEP")]
DROP FUNCTION SLEEP;
[/#if]

CREATE FUNCTION SLEEP(SECONDS DOUBLE) RETURNS INT
PARAMETER STYLE JAVA NO SQL LANGUAGE JAVA
EXTERNAL NAME '${exportedDbUtilsClass}.sleepSeconds'
