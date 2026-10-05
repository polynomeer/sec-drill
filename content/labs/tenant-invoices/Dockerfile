# Synthetic tenant-invoices Lab image (T07, T08). Built locally for local-trusted development; not pushed anywhere.
# Base: python:3.14-alpine pinned by index digest. No packages are installed; the app uses the standard library.
# The same image serves the learner Lab and, in a separate fresh container, patch grading.
FROM python@sha256:f6a589d43c42b9e7f7dc67a12d37132491f362859a5d750607710cc56da3bc72
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY app/ /app/app/
USER 65534:65534
EXPOSE 8080
CMD ["python", "-m", "app.server"]
