[#if sleep]
SELECT pg_sleep(0.06)
[#else]
[#include "/common/query1.sql.ftl"]
[/#if]
