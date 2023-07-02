[#if sleep]
{call dbo.SLEEP('00:00:00.06')}
[#else]
[#include "/common/query2.sql.ftl"]
[/#if]
