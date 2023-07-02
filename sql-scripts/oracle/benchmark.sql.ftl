[#if sleep]
{call DBMS_SESSION.SLEEP(0.06)}
[#else]
[#include "/common/query-oracle.sql.ftl"]
[/#if]
