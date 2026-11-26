FROM python:3.13-slim

ENV PYTHONUNBUFFERED=1 \
    WEB_PORT=8080

WORKDIR /app
COPY hibylink.py /app/hibylink.py
COPY webapp /app/webapp

# Run as an unprivileged user; ports 4001/4003 (discovery) are > 1024.
RUN useradd --system --no-create-home hiby
USER hiby

EXPOSE 8080
CMD ["python", "/app/webapp/server.py"]
