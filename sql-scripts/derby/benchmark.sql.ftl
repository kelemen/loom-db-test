[#if sleep]
SELECT SLEEP(0.06) AS X FROM SYSIBM.SYSDUMMY1
[#else]
[#include "/common/query1.sql.ftl"]
[/#if]
