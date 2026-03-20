  for f in test-docs/*.pdf; do
  echo "Ingesting: $f"
  curl -s -X POST http://localhost:8087/api/v1/documents/ingest \
  -F "file=@$f" && echo ""
done