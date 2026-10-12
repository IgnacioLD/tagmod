FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY tagmod_server.py .
USER nobody
EXPOSE 8765
# Mount the config (and the tasks file it points to) at /config and /data.
ENV TAGMOD_CONFIG=/config/config.toml
HEALTHCHECK CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8765/health')"
CMD ["python", "tagmod_server.py"]
