#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Vérifie qu'un serveur GeoNature expose les endpoints utilisés par GeoMys-Android,
et signale pour chacun : OK (conforme) / ATTENTION (repli/dégradation, non bloquant) /
ECHEC (à investiguer).

Usage :
    python3 verifier_serveur_geonature.py <url_geonature> [login] [password] [id_application]

Exemples :
    python3 verifier_serveur_geonature.py https://geonature.exemple.fr/geonature
    python3 verifier_serveur_geonature.py https://geonature.exemple.fr/geonature it@x.fr motdepasse

Sans login : seuls les endpoints publics sont concluants ; ceux qui exigent une
authentification ressortent en ATTENTION (login requis). Aucune écriture n'est faite
sur le serveur (uniquement des lectures + un POST CRUVED de filtrage).
"""
import sys
import json
import http.cookiejar
import urllib.request
import urllib.error

G = "\033[32m"; Y = "\033[33m"; R = "\033[31m"; Z = "\033[0m"
OK = f"{G}  OK  {Z}"; WARN = f"{Y}ATTENT{Z}"; KO = f"{R}ECHEC {Z}"


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    base = sys.argv[1].rstrip("/")
    api = base + "/api"
    login = sys.argv[2] if len(sys.argv) > 2 else None
    pwd = sys.argv[3] if len(sys.argv) > 3 else None
    id_app = int(sys.argv[4]) if len(sys.argv) > 4 else 1

    cj = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    opener.addheaders = [("User-Agent", "geomys-verif")]

    def call(path, payload=None):
        url = api + path
        data = json.dumps(payload).encode() if payload is not None else None
        headers = {"Content-Type": "application/json"} if data else {}
        req = urllib.request.Request(url, data=data, headers=headers)
        try:
            with opener.open(req, timeout=25) as r:
                return r.getcode(), r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8", "replace")
        except Exception as e:  # noqa: BLE001
            return None, str(e)

    def as_list(body):
        d = json.loads(body)
        return d.get("data", d) if isinstance(d, dict) else d

    def is_json(body):
        try:
            json.loads(body)
            return True
        except Exception:  # noqa: BLE001
            return False  # typiquement une page HTML de login → endpoint protégé

    def show(status, label, detail=""):
        print(f"  [{status}] {label}" + (f" — {detail}" if detail else ""))

    print(f"== Vérification GeoNature : {base} ==")

    authed = False
    if login:
        code, _ = call("/auth/login", {"login": login, "password": pwd, "id_application": id_app})
        if code == 200:
            authed = True
            show(OK, "Authentification")
        else:
            show(WARN, "Authentification", f"HTTP {code} — poursuite en accès public")
    else:
        show(WARN, "Authentification", "non fournie — les endpoints protégés ressortiront en ATTENTION")

    # 1) Config → version GeoNature + liste d'observateurs + liste habitat configurées (comme le web)
    id_obs = None
    taxa_list_id = None   # settings.sync.taxa_list_id (liste TaxRef de référence) — pour le test TaxRef
    first_list_id = None  # 1ʳᵉ liste de biblistes — repli pour le test TaxRef
    code, body = call("/gn_commons/config")
    if code == 200:
        try:
            cfg = json.loads(body) or {}
            # Version GeoNature (minimum supporté par l'app : 2.15).
            version = cfg.get("GEONATURE_VERSION") or "?"
            try:
                parts = [int(x) for x in str(version).split(".")[:2]]
                trop_vieux = parts < [2, 15]
            except Exception:  # noqa: BLE001
                trop_vieux = False
            show(WARN if trop_vieux else OK, "Version GeoNature",
                 f"{version}" + (" — ⚠ < 2.15, non supporté (TaxHub intégré requis)" if trop_vieux else ""))
            occ = cfg.get("OCCTAX", {}) or {}
            id_obs = occ.get("id_observers_list")
            show(OK, "gn_commons/config (section OCCTAX)",
                 f"id_observers_list={id_obs}, ID_LIST_HABITAT={occ.get('ID_LIST_HABITAT')}")
        except Exception as e:  # noqa: BLE001
            show(WARN, "gn_commons/config", f"JSON inattendu ({e}) → l'app retombera sur /users/roles")
    else:
        show(WARN, "gn_commons/config", f"HTTP {code} → version GeoNature indétectable ; l'app retombera sur /users/roles")

    # 2) Liste d'observateurs curée (source du web)
    if id_obs:
        code, body = call(f"/users/menu/{id_obs}")
        if code == 200:
            try:
                arr = as_list(body)
                struct_ok = bool(arr) and isinstance(arr[0], dict) and {"id_role", "nom_complet"} <= set(arr[0].keys())
                show(OK if struct_ok else WARN, f"users/menu/{id_obs} (observateurs web)",
                     f"{len(arr)} entrées" + ("" if struct_ok else " ⚠ champs id_role/nom_complet absents"))
            except Exception as e:  # noqa: BLE001
                show(KO, f"users/menu/{id_obs}", f"JSON inattendu ({e})")
        else:
            show(KO if authed else WARN, f"users/menu/{id_obs}", f"HTTP {code}")
    else:
        show(WARN, "users/menu/<id>", "pas d'id_observers_list → l'app utilisera /users/roles (tous les rôles)")

    # 3) Repli : tous les rôles
    code, body = call("/users/roles")
    if code == 200 and is_json(body):
        show(OK, "users/roles (repli observateurs)", f"{len(as_list(body))} rôles")
    else:
        show(WARN, "users/roles",
             f"HTTP {code}" + ("" if authed else " — réponse non-JSON (login requis ?)"))

    # 4) Habitat (HABREF) — recherche live
    code, body = call("/habref/habitats/autocomplete?search_name=prairie&limit=3")
    if code == 200:
        try:
            arr = json.loads(body)
            ok = bool(arr) and "cd_hab" in arr[0] and "search_name" in arr[0]
            show(OK if ok else WARN, "habref/habitats/autocomplete",
                 f"{len(arr)} résultats" + ("" if ok else " (champs cd_hab/search_name absents)"))
        except Exception as e:  # noqa: BLE001
            show(KO, "habref/habitats/autocomplete", f"JSON inattendu ({e})")
    else:
        show(WARN, "habref/habitats/autocomplete", f"HTTP {code} — habitat indisponible (non bloquant)")

    # 5) settings.json mobile (champs/nomenclatures pilotés serveur)
    code, body = call("/gn_commons/t_mobile_apps")
    if code == 200:
        try:
            apps = as_list(body)
            occ = next((a for a in apps if (a.get("app_code") or a.get("code")) == "OCCTAX"), None)
            if occ:
                s = occ.get("settings") or {}
                keys = list(s.keys()) if isinstance(s, dict) else type(s).__name__
                taxa_list_id = (s.get("sync") or {}).get("taxa_list_id") if isinstance(s, dict) else None
                show(OK, "t_mobile_apps (package OCCTAX)", f"settings={keys}, taxa_list_id={taxa_list_id}")
            else:
                show(WARN, "t_mobile_apps", "pas de package OCCTAX → réglages par défaut côté app")
        except Exception as e:  # noqa: BLE001
            show(WARN, "t_mobile_apps", f"JSON inattendu ({e})")
    else:
        show(WARN, "t_mobile_apps", f"HTTP {code} — settings.json absent (défauts appliqués)")

    # 6) Jeux de données lisibles (OCCTAX)
    code, body = call("/meta/datasets?active=true&module_code=OCCTAX&fields=modules")
    ok6 = code == 200 and is_json(body)
    show(OK if ok6 else WARN, "meta/datasets (lecture OCCTAX)",
         "" if ok6 else f"HTTP {code}" + ("" if authed else " — login requis"))

    # 7) Jeux de données CRÉABLES (CRUVED C) — filtre du formulaire web
    code, body = call("/meta/datasets?active=true", {"create": "OCCTAX"})
    ok7 = code == 200 and is_json(body)
    show(OK if ok7 else WARN, "meta/datasets POST create=OCCTAX (CRUVED)",
         "" if ok7 else f"HTTP {code}" + ("" if authed else " — login requis"))

    # 8) Version TaxRef (skip de rechargement)
    code, body = call("/taxhub/api/taxref/version")
    ok8 = code == 200 and not body.lstrip().startswith("<")
    detail8 = f"HTTP {code}"
    if ok8:
        try:
            d = json.loads(body)
            detail8 = f"TaxRef v{d.get('version') or d.get('taxref_version') or body.strip()}"
        except Exception:  # noqa: BLE001
            detail8 = body.strip()[:40]
    show(OK if ok8 else WARN, "taxhub/api/taxref/version", detail8)

    # 9) Listes de taxons TaxHub (bib_listes) — l'app charge les listes via {urlTaxhub}/api/biblistes.
    # URL canonique avec slash final (la version sans slash fait un 308, suivi par l'app Android) ;
    # on teste la forme canonique pour un verdict net. 404 ici = échec probable du chargement des listes.
    code, body = call("/taxhub/api/biblistes/")
    if code == 200 and is_json(body):
        listes = as_list(body)
        if listes and isinstance(listes[0], dict):
            first_list_id = listes[0].get("id_liste")
        struct_ok = bool(listes) and isinstance(listes[0], dict) and "id_liste" in listes[0]
        show(OK if struct_ok else WARN, "taxhub/api/biblistes (listes de taxons)",
             f"{len(listes)} liste(s)" + ("" if struct_ok else " ⚠ champ id_liste absent"))
    elif code == 404:
        show(KO, "taxhub/api/biblistes", "404 — endpoint absent (TaxHub mal monté ?) → chargement des listes en échec")
    else:
        show(WARN, "taxhub/api/biblistes", f"HTTP {code}" + ("" if authed else " (login requis ?)"))

    # 10) Nomenclatures (taxonomy) — synchronisées par l'app via /nomenclatures/nomenclatures/taxonomy.
    code, body = call("/nomenclatures/nomenclatures/taxonomy")
    if code == 200 and is_json(body):
        types = as_list(body)
        struct_ok = (bool(types) and isinstance(types[0], dict)
                     and "mnemonique" in types[0] and "nomenclatures" in types[0])
        show(OK if struct_ok else WARN, "nomenclatures/taxonomy",
             f"{len(types)} type(s)" + ("" if struct_ok else " ⚠ structure inattendue (mnemonique/nomenclatures)"))
    else:
        show(WARN, "nomenclatures/taxonomy", f"HTTP {code}" + ("" if authed else " (login requis ?)"))

    # 11) Téléchargement TaxRef — LE gros payload (suspect mémoire/lenteur au chargement). On vise
    # la liste configurée (taxa_list_id) sinon la 1ʳᵉ de biblistes. URL avec slash final (sans slash
    # = 308, suivi par l'app Android). Affiche la TAILLE de la liste (total_filtered) + valide les champs.
    lid = taxa_list_id or first_list_id
    if lid:
        code, body = call(f"/taxhub/api/taxref/?orderby=cd_nom&fields=listes&id_liste={lid}&limit=2&page=1")
        if code == 200 and is_json(body):
            try:
                d = json.loads(body)
                items = d.get("items") if isinstance(d, dict) else d
                total = d.get("total_filtered") if isinstance(d, dict) else None
                t0 = items[0] if items else {}
                struct_ok = isinstance(t0, dict) and "cd_nom" in t0 and ("nom_complet" in t0 or "nom_valide" in t0)
                detail = (f"liste {lid} : {total} taxons" if total is not None else f"liste {lid}")
                if total and total > 100000:
                    detail += " — volumineux (synchro longue, mémoire à surveiller)"
                if not struct_ok:
                    detail += " ⚠ champs cd_nom/nom_* absents (parsing risqué)"
                show(WARN if not struct_ok else OK, "taxhub/api/taxref (téléchargement)", detail)
            except Exception as e:  # noqa: BLE001
                show(KO, "taxhub/api/taxref", f"JSON inattendu ({e})")
        elif code == 404:
            show(KO, "taxhub/api/taxref", "404 — endpoint absent → chargement des taxons en échec")
        else:
            show(WARN, "taxhub/api/taxref", f"HTTP {code}" + ("" if authed else " (login requis ?)"))
    else:
        show(WARN, "taxhub/api/taxref (téléchargement)", "aucune liste connue (biblistes vide / taxa_list_id absent)")

    print(f"\nLégende : [{OK}] conforme · [{WARN}] repli/dégradation (non bloquant) · [{KO}] à investiguer")
    print("Un ECHEC sur users/menu ou un ATTENTION généralisé = vérifier version GeoNature / droits / config.")


if __name__ == "__main__":
    main()
