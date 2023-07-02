[#if sleep]
SELECT SLEEP(0.06) AS X FROM INFORMATION_SCHEMA.SYSTEM_USERS
[#else]
[#include "/common/insert-delete.sql.ftl"]
[/#if]
