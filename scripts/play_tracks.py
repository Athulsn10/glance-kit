"""List the app's Google Play tracks and their releases (read-only check of Play access)."""
import json, os
from google.oauth2 import service_account
from googleapiclient.discovery import build

creds = service_account.Credentials.from_service_account_info(
    json.loads(os.environ["PLAY_SERVICE_ACCOUNT_JSON"]),
    scopes=["https://www.googleapis.com/auth/androidpublisher"],
)
pkg = os.environ["PACKAGE_NAME"]
api = build("androidpublisher", "v3", credentials=creds, cache_discovery=False)
edit = api.edits().insert(packageName=pkg, body={}).execute()
try:
    for t in api.edits().tracks().list(packageName=pkg, editId=edit["id"]).execute().get("tracks", []):
        print(f"track: {t['track']}")
        for r in t.get("releases", []):
            print(f"   {r.get('name', '')}  status={r.get('status')}  versionCodes={r.get('versionCodes')}")
finally:
    api.edits().delete(packageName=pkg, editId=edit["id"]).execute()
