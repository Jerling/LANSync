#!/usr/bin/env python3
"""
迁移脚本：将旧全局照片迁移到按用户隔离的目录结构。

旧结构:
  base_dir/
  ├── manifest.json
  ├── _thumbs/
  ├── _partial/
  └── YYYY/MM/DD/

新结构:
  base_dir/
  ├── default/           # 默认用户目录（迁移时分配）
  │   ├── manifest.json
  │   ├── _thumbs/
  │   ├── _partial/
  │   └── YYYY/MM/DD/
  └── username1/
      ├── manifest.json
      └── ...

用法:
  python migrate_to_user_isolation.py [--base-dir /path/to/Photos] [--users user1,user2]

不指定用户时，会提示手动输入（一个文件只能分配给一个用户）。
"""
import os
import sys
import json
import shutil
import argparse
from pathlib import Path


def load_old_manifest(base_dir: Path) -> dict:
    manifest_path = base_dir / "manifest.json"
    if not manifest_path.exists():
        return {}
    with open(manifest_path, "r", encoding="utf-8") as f:
        return json.load(f)


def save_user_manifest(user_dir: Path, manifest: dict):
    user_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = user_dir / "manifest.json"
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)


def migrate_with_user_assignment(base_dir: Path, users: list[str]):
    """
    交互式迁移：遍历旧 manifest，将每个文件分配给指定用户。
    如果用户列表只有一个用户，直接全部分配给该用户。
    """
    manifest = load_old_manifest(base_dir)
    if not manifest:
        print("No manifest found, nothing to migrate.")
        return

    # 收集所有需要迁移的文件
    files_to_migrate = []
    for key, entry in manifest.items():
        if len(key) != 64:
            continue  # 跳过非哈希 key
        if isinstance(entry, list):
            entry = entry[0] if entry else {}
        saved_path = entry.get("saved_path", "")
        if not saved_path:
            continue
        full_path = base_dir / saved_path
        if full_path.exists():
            files_to_migrate.append({
                "hash": key,
                "entry": entry,
                "saved_path": saved_path,
                "full_path": full_path
            })

    if not files_to_migrate:
        print("No files to migrate.")
        return

    print(f"Found {len(files_to_migrate)} files to migrate.")

    # 单用户直接迁移
    if len(users) == 1:
        target_user = users[0]
        print(f"\nSingle user '{target_user}' detected — migrating all files directly.")
        user_dir = base_dir / target_user
        new_manifest = {}
        thumbs_dir = user_dir / "_thumbs"
        thumbs_dir.mkdir(parents=True, exist_ok=True)

        for item in files_to_migrate:
            # 复制文件到用户目录
            rel_path = item["saved_path"]
            new_user_path = user_dir / rel_path
            new_user_path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(item["full_path"], new_user_path)

            # 更新 manifest
            entry = dict(item["entry"])
            entry["saved_path"] = rel_path
            new_manifest[item["hash"]] = [entry]

            # 复制缩略图
            thumb_id = hashlib.md5(rel_path.encode("utf-8")).hexdigest()
            old_thumb = base_dir / "_thumbs" / f"{thumb_id}.jpg"
            if old_thumb.exists():
                new_thumb = thumbs_dir / f"{thumb_id}.jpg"
                shutil.copy2(old_thumb, new_thumb)

        save_user_manifest(user_dir, new_manifest)
        print(f"\nMigration complete! {len(files_to_migrate)} files migrated to '{target_user}'.")
        return

    # 多用户交互式分配
    print(f"\nMultiple users detected: {users}")
    print("Files will be listed one by one. Assign each to a user by number.\n")

    # 按用户初始化
    user_manifests = {u: {} for u in users}
    user_thumbs_dirs = {}

    for i, item in enumerate(files_to_migrate, 1):
        rel_path = item["saved_path"]
        orig_name = item["entry"].get("original_name", rel_path)
        print(f"[{i}/{len(files_to_migrate)}] {orig_name} ({item['hash'][:16]}...)")
        for j, u in enumerate(users, 1):
            print(f"  {j}: {u}")
        print(f"  0: skip this file")

        choice = input(f"  Assign to user (0-{len(users)}) [default: 1]: ").strip()
        if choice == "":
            choice = "1"
        try:
            idx = int(choice)
        except ValueError:
            idx = 1

        if idx < 0 or idx > len(users):
            idx = 1

        if idx == 0:
            print("  Skipped.")
            continue

        target_user = users[idx - 1]
        if target_user not in user_thumbs_dirs:
            user_thumbs_dirs[target_user] = (base_dir / target_user / "_thumbs")
            user_thumbs_dirs[target_user].mkdir(parents=True, exist_ok=True)

        # 复制文件
        user_dir = base_dir / target_user
        new_user_path = user_dir / rel_path
        new_user_path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(item["full_path"], new_user_path)

        # 更新 manifest
        entry = dict(item["entry"])
        entry["saved_path"] = rel_path
        if item["hash"] not in user_manifests[target_user]:
            user_manifests[target_user][item["hash"]] = []
        user_manifests[target_user][item["hash"]].append(entry)

        # 复制缩略图
        thumb_id = hashlib.md5(rel_path.encode("utf-8")).hexdigest()
        old_thumb = base_dir / "_thumbs" / f"{thumb_id}.jpg"
        if old_thumb.exists():
            new_thumb = user_thumbs_dirs[target_user] / f"{thumb_id}.jpg"
            shutil.copy2(old_thumb, new_thumb)

        print(f"  -> {target_user}\n")

    # 保存各用户 manifest
    for user, m in user_manifests.items():
        if m:
            user_dir = base_dir / user
            save_user_manifest(user_dir, m)
            print(f"User '{user}': {len(m)} files migrated.")

    print("\nMigration complete!")


def dry_run(base_dir: Path):
    """只预览，不执行"""
    manifest = load_old_manifest(base_dir)
    files = [(k, v) for k, v in manifest.items() if len(k) == 64]
    print(f"Dry run — manifest has {len(files)} entries")
    for key, entry in files[:10]:
        entry = entry[0] if isinstance(entry, list) else entry
        print(f"  {entry.get('original_name', '?')} -> {entry.get('saved_path', '?')}")
    if len(files) > 10:
        print(f"  ... and {len(files) - 10} more")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Migrate LANSync to per-user storage")
    parser.add_argument("--base-dir", default="/home/jer/data/Photos", help="Photo base directory")
    parser.add_argument("--users", default="", help="Comma-separated user list (e.g. alice,bob)")
    parser.add_argument("--dry-run", action="store_true", help="Preview without migrating")
    args = parser.parse_args()

    base_dir = Path(args.base_dir)
    if not base_dir.exists():
        print(f"Directory not found: {base_dir}")
        sys.exit(1)

    if args.dry_run:
        dry_run(base_dir)
        sys.exit(0)

    users = [u.strip() for u in args.users.split(",") if u.strip()]
    if not users:
        print("No users specified. Use --users alice,bob or --dry-run to preview.")
        sys.exit(1)

    import hashlib  # for md5 in migrate_with_user_assignment
    migrate_with_user_assignment(base_dir, users)
