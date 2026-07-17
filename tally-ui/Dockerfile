FROM node:16-bullseye AS build

WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build

FROM python:3.11-slim

WORKDIR /app

COPY --from=build /app/dist ./dist
COPY serve_dist.py .

EXPOSE 4200

CMD ["python", "serve_dist.py"]
