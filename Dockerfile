FROM python:3.12-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONUTF8=1 \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

WORKDIR /app
COPY server/requirements.lock /tmp/requirements.lock
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install -r /tmp/requirements.lock supervisor==4.3.0
COPY server/pyproject.toml /app/pyproject.toml
COPY server/src /app/src
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install --no-deps .

RUN useradd --create-home --uid 10001 nganyaflow \
    && mkdir -p /app/data/raw /app/data/processed /app/artifacts /app/runtime \
    && chown -R nganyaflow:nganyaflow /app
COPY --chown=nganyaflow:nganyaflow server/data/reference /app/data/reference
COPY deploy/supervisord.conf /etc/supervisor/supervisord.conf

USER nganyaflow
ENV NGANYAFLOW_DATABASE=/app/data/raw/observations.sqlite3 \
    NGANYAFLOW_CATALOG=/app/data/reference/catalog.json \
    NGANYAFLOW_RUNTIME_DIR=/app/runtime
EXPOSE 8000
VOLUME ["/app/data/raw", "/app/artifacts", "/app/runtime"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=3)"
CMD ["supervisord", "-c", "/etc/supervisor/supervisord.conf"]
