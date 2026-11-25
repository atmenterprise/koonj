FROM quay.io/keycloak/keycloak:26.8.2

COPY deploy/keycloak/themes/ /opt/keycloak/themes/
COPY koonj-ui/public/img/atm-banner-16x9-clean.jpg \
     koonj-ui/public/img/koonj-mark.svg \
     koonj-ui/public/favicon.ico \
     /opt/keycloak/themes/koonj/login/resources/img/
