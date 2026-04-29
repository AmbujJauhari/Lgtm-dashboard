FROM python:3.12-slim

# Install deps into the image — templates/config/library_panels are mounted at runtime
COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt

WORKDIR /workspace

ENTRYPOINT ["python", "provisioner/main.py"]
