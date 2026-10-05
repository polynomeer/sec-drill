# Synthetic api-gateway Lab image (T13). Built locally for local-trusted development; not pushed anywhere.
FROM python@sha256:f6a589d43c42b9e7f7dc67a12d37132491f362859a5d750607710cc56da3bc72
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY app/ /app/app/
USER 65534:65534
EXPOSE 8080
CMD ["python", "-m", "app.server"]
