#!/bin/bash

keytool -genkeypair \
  -alias salon-alias \
  -keyalg RSA \
  -keysize 2048 \
  -validity 365 \
  -storetype PKCS12 \
  -keystore salon-boot/src/main/resources/certs/salon-keystore.p12 \
  -storepass MySecurePassword123 \
  -dname "CN=localhost, OU=Development, O=Salon, L=Minsk, C=BY" \
  -ext SAN=ip:::1
