# nginx with the built SPA. CI builds frontend/dist first (12a decision 1).
FROM nginx:1.30.1-alpine

RUN rm /etc/nginx/conf.d/default.conf
COPY deploy/nginx/nginx.conf /etc/nginx/nginx.conf
COPY deploy/nginx/locations.conf deploy/nginx/proxy.conf /etc/nginx/snippets/
COPY deploy/nginx/http-only.conf.template deploy/nginx/https.conf.template /etc/nginx/tls-templates/
COPY --chmod=0755 deploy/nginx/18-select-tls.sh /docker-entrypoint.d/18-select-tls.sh
COPY frontend/dist /usr/share/nginx/html
