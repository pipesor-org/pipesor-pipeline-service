"""Preview GHCR retention from operator-supplied snapshots; never contacts a registry."""

import argparse
import json
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path


MINIMUM_CANDIDATES = 10
MAX_PROTECTION_AGE_HOURS = 24
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
TECHNICAL_TAG = re.compile(r"sha-[0-9a-f]{40}-[1-9][0-9]*-[1-9][0-9]*")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def timestamp(value):
    require(isinstance(value, str)
            and re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|\+00:00)", value),
            "Timestamps must be UTC ISO-8601")
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        raise ValueError("Invalid timestamp") from None


def verified_digests(value):
    require(isinstance(value, dict) and value.get("verified") is True,
            "Protection scopes and releases require explicit operator verification")
    digests = value.get("digests")
    require(isinstance(digests, list)
            and all(isinstance(item, str) and DIGEST.fullmatch(item) for item in digests),
            "Invalid protection digests")
    return set(digests)


def preview(inventory, protection, image, *, age_days=30, required_environments=("dev",), now=None):
    now = now or datetime.now(timezone.utc)
    require(isinstance(image, str) and re.fullmatch(r"ghcr\.io/[a-z0-9_.-]+/[a-z0-9_.-]+", image),
            "Invalid GHCR image")
    require(type(age_days) is int and age_days > 0, "Age must be a positive number of days")
    require(isinstance(required_environments, (tuple, list)) and required_environments
            and all(isinstance(name, str) and re.fullmatch(r"[a-z][a-z0-9-]*", name)
                    for name in required_environments), "Required environments must be explicit")
    require(isinstance(protection, dict) and type(protection.get("schema_version")) is int
            and protection["schema_version"] == 1, "Unsupported protection schema")
    require(protection.get("image") == image, "Protection image mismatch")
    captured_at = timestamp(protection.get("captured_at"))
    require(timedelta(0) <= now - captured_at <= timedelta(hours=MAX_PROTECTION_AGE_HOURS),
            "Protection snapshot is stale or from the future")
    environments = protection.get("environments")
    require(isinstance(environments, dict) and all(name in environments for name in required_environments),
            "Missing required protection environment")
    protected = verified_digests(protection.get("releases"))
    for scope in environments.values():
        protected.update(verified_digests(scope))

    require(isinstance(inventory, list), "Inventory must be a GitHub Packages version list")
    versions, ids, digests = [], set(), set()
    for version in inventory:
        require(isinstance(version, dict), "Invalid inventory version")
        version_id, digest = version.get("id"), version.get("name")
        require(type(version_id) is int and version_id > 0 and version_id not in ids,
                "Invalid or duplicate inventory version ID")
        require(isinstance(digest, str) and DIGEST.fullmatch(digest) and digest not in digests,
                "Invalid or duplicate inventory digest")
        created_at = timestamp(version.get("created_at"))
        require(created_at <= now, "Inventory creation time is in the future")
        metadata = version.get("metadata")
        require(isinstance(metadata, dict) and isinstance(metadata.get("container"), dict),
                "Missing container metadata")
        tags = metadata["container"].get("tags")
        require(isinstance(tags, list) and all(isinstance(tag, str) for tag in tags),
                "Invalid container tags")
        technical = bool(tags) and all(TECHNICAL_TAG.fullmatch(tag) for tag in tags)
        versions.append((version_id, digest, created_at, technical))
        ids.add(version_id)
        digests.add(digest)

    newest = {entry[0] for entry in sorted((entry for entry in versions if entry[3]),
                                          key=lambda entry: (entry[2], entry[0]), reverse=True)
              [:MINIMUM_CANDIDATES]}
    result = {
        "schema_version": 1, "preview_only": True, "image": image,
        "generated_at": now.isoformat(),
        "policy": {"age_days": age_days, "minimum_candidates": MINIMUM_CANDIDATES,
                   "max_protection_age_hours": MAX_PROTECTION_AGE_HOURS,
                   "required_environments": list(required_environments),
                   "protection_captured_at": captured_at.isoformat()},
        "keep": [], "proposed_delete": [],
    }
    for version_id, digest, created_at, technical in versions:
        reasons = []
        if digest in protected:
            reasons.append("protected_digest")
        if not technical:
            reasons.append("untagged_or_nontechnical_tags")
        if version_id in newest:
            reasons.append("newest_technical_candidates")
        if now - created_at < timedelta(days=age_days):
            reasons.append("recent_candidate")
        decision = "keep" if reasons else "proposed_delete"
        result[decision].append({"id": version_id, "digest": digest,
                                 "reasons": reasons or ["old_unprotected_technical_candidate"]})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--inventory", required=True, type=Path)
    parser.add_argument("--protection", required=True, type=Path)
    parser.add_argument("--age-days", type=int, default=30)
    parser.add_argument("--required-environment", action="append", dest="environments",
                        help="Required verified environment; repeat for every target (default: dev)")
    args = parser.parse_args()
    try:
        result = preview(json.loads(args.inventory.read_text()), json.loads(args.protection.read_text()),
                         args.image, age_days=args.age_days, required_environments=args.environments or ["dev"])
    except (OSError, UnicodeError, ValueError):
        parser.exit(2, "Retention preview rejected: invalid, incomplete or stale input; no plan produced.\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
