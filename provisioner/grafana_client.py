import requests


class GrafanaError(Exception):
    pass


class GrafanaClient:
    def __init__(self, base_url: str, token: str = None, user: str = None, password: str = None):
        self.base_url = base_url.rstrip("/")
        self.session  = requests.Session()
        self.session.headers.update({"Content-Type": "application/json", "Accept": "application/json"})
        if token:
            self.session.headers["Authorization"] = f"Bearer {token}"
        elif user and password:
            self.session.auth = (user, password)

    def _url(self, path: str) -> str:
        return f"{self.base_url}{path}"

    def _raise(self, r: requests.Response, context: str):
        try:
            detail = r.json()
        except Exception:
            detail = r.text
        raise GrafanaError(f"{context} — HTTP {r.status_code}: {detail}")

    # ── Folders ───────────────────────────────────────────────────────────────

    def get_folder(self, uid: str) -> dict | None:
        r = self.session.get(self._url(f"/api/folders/{uid}"))
        if r.status_code == 404:
            return None
        if not r.ok:
            self._raise(r, f"get_folder/{uid}")
        return r.json()

    def create_folder(self, title: str, uid: str, parent_uid: str = None) -> dict:
        payload = {"title": title, "uid": uid}
        if parent_uid:
            payload["parentUid"] = parent_uid
        r = self.session.post(self._url("/api/folders"), json=payload)
        if not r.ok:
            self._raise(r, f"create_folder/{title}")
        return r.json()

    # ── Dashboards ────────────────────────────────────────────────────────────

    def upsert_dashboard(self, dashboard: dict, folder_uid: str) -> dict:
        payload = {
            "dashboard": dashboard,
            "folderUid": folder_uid,
            "overwrite": True,
            "message": "Provisioned by collops-provisioner",
        }
        r = self.session.post(self._url("/api/dashboards/db"), json=payload)
        if not r.ok:
            self._raise(r, f"upsert_dashboard/{dashboard.get('title')}")
        print(f"  [upserted] dashboard: {dashboard.get('title')}  uid={dashboard.get('uid')}")
        return r.json()

    # ── Library Elements ──────────────────────────────────────────────────────

    def upsert_library_element(self, panel: dict, folder_uid: str = None) -> dict:
        uid = panel["uid"]
        r   = self.session.get(self._url(f"/api/library-elements/{uid}"))

        if r.status_code == 200:
            existing = r.json()["result"]
            payload  = {
                "name":    panel["name"],
                "model":   panel["model"],
                "kind":    1,
                "version": existing["version"],
            }
            r2 = self.session.patch(self._url(f"/api/library-elements/{uid}"), json=payload)
            if not r2.ok:
                self._raise(r2, f"update_library_element/{uid}")
            print(f"  [updated]  library panel: {panel['name']}")
            return r2.json()["result"]

        payload = {"uid": uid, "name": panel["name"], "kind": 1, "model": panel["model"]}
        if folder_uid:
            payload["folderUid"] = folder_uid
        r2 = self.session.post(self._url("/api/library-elements"), json=payload)
        if not r2.ok:
            self._raise(r2, f"create_library_element/{uid}")
        print(f"  [created]  library panel: {panel['name']}")
        return r2.json()["result"]

    # ── Misc ──────────────────────────────────────────────────────────────────

    def health(self) -> bool:
        try:
            return self.session.get(self._url("/api/health"), timeout=5).ok
        except Exception:
            return False
