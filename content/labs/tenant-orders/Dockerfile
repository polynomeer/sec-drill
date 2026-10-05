# Synthetic tenant-orders Lab image (T07). Built locally for local-trusted development; not pushed anywhere.
# Base: python:3.14-alpine pinned by index digest. No packages are installed; the app uses the standard library.
FROM python@sha256:f6a589d43c42b9e7f7dc67a12d37132491f362859a5d750607710cc56da3bc72
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
COPY app/app.py /app/app.py
USER 65534:65534
EXPOSE 8080
CMD ["python", "/app/app.py"]
