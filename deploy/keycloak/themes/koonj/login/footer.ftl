<#import "social-providers.ftl" as identityProviders>

<#macro content>
  <#-- The parent login form already renders providers. Its registration form uses this footer. -->
  <#if register?? && social.providers?has_content>
    <@identityProviders.show social=social/>
  </#if>
  <footer class="koonj-copyright">© ${.now?string("yyyy")} Apps That Matter</footer>
</#macro>
