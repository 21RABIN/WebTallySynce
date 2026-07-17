import asyncio
import logging
from typing import Dict, Any, List

from .config import get_settings
from .tally_client import TallyClient
from .xml_builder import build_master_upsert, build_master_list
from .routes import xml_to_json

log = logging.getLogger(__name__)


# Minimal reconciliation: pull from source, push to target for a master type
async def _sync_direction(
    source: TallyClient,
    target: TallyClient,
    company: str,
    master: str,
    fetch_fields: List[str] | None = None,
    filter_expr: str | None = None,
):
    xml_req = build_master_list(company, master, fetch_fields, filter_expr)
    try:
        xml_resp = await source.post_xml(xml_req)
    except Exception as exc:  # noqa: BLE001
        log.warning("export failed master=%s err=%s", master, exc)
        return
    data = xml_to_json(xml_resp)
    masters = _flatten_records(data)
    for record in masters:
        try:
            upsert_xml = build_master_upsert(company, master, "Alter", record)
            await target.post_xml(upsert_xml)
        except Exception as exc:  # noqa: BLE001 broad to continue sync
            log.warning("sync skip %s record=%s err=%s", master, record, exc)


def _flatten_records(parsed: Dict[str, Any]) -> List[Dict[str, Any]]:
    # best-effort extraction of LIST items
    try:
        collection = parsed["ENVELOPE"]["BODY"]["EXPORTDATA"]["REQUESTDESC"]["TDL"]["TDLMESSAGE"]["COLLECTION"]
    except Exception:
        return []
    if isinstance(collection, list):
        items = collection
    else:
        items = [collection]
    records: List[Dict[str, Any]] = []
    for item in items:
        # Each item may be a dict of fields; keep as-is
        if isinstance(item, dict):
            records.append(item)
    return records


async def continuous_sync():
    settings = get_settings()
    if not settings.sync_enabled:
        log.info("sync disabled; exiting")
        return
    if not settings.server_base_url:
        log.warning("sync enabled but SERVER_BASE_URL missing; exiting")
        return

    local = TallyClient(settings.tally_base_url, agent_key=settings.agent_key)
    remote = TallyClient(settings.server_base_url, agent_key=settings.server_agent_key)

    # if server points directly to Tally XML port, we should only push local->server (as Tally cannot pull back)
    if settings.server_mode.lower() == "tally":
        if settings.sync_direction == "pull":
            log.warning("server_mode=tally cannot pull -> forcing push only")
            settings.sync_direction = "push"
        elif settings.sync_direction == "both":
            log.warning("server_mode=tally cannot both -> forcing push only")
            settings.sync_direction = "push"

    master_types = [
        "company",
        "currency",
        "group",
        "ledger",
        "cost category",
        "cost centre",
        "unit",
        "godown",
        "stock group",
        "stock category",
        "stock item",
        "bom",
        "price level",
        "voucher type",
        "budget",
        "employee",
        "employee group",
        "pay head",
        "attendance type",
    ]

    log.info("starting continuous sync direction=%s interval=%ss", settings.sync_direction, settings.sync_interval_sec)
    try:
        while True:
            tasks = []
            if settings.sync_direction in ("push", "both"):
                # local -> server
                for m in master_types:
                    tasks.append(_sync_direction(local, remote, settings.company, m))
            if settings.sync_direction in ("pull", "both"):
                # server -> local
                for m in master_types:
                    tasks.append(_sync_direction(remote, local, settings.company, m))
            if tasks:
                await asyncio.gather(*tasks)
            await asyncio.sleep(settings.sync_interval_sec)
    finally:
        await local.close()
        await remote.close()
