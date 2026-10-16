# ---- frontend build ----
FROM node:20-alpine AS web
WORKDIR /build/web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-fund --no-audit
COPY web/ ./
RUN npm run build

# ---- backend ----
FROM clojure:temurin-21-tools-deps-alpine
WORKDIR /app
# warm the dependency cache first so source edits don't re-download the world
COPY deps.edn ./
RUN clojure -P -M:run
COPY src ./src
COPY resources ./resources
COPY --from=web /build/web/dist ./web/dist
EXPOSE 8070
CMD ["clojure", "-M:run"]
