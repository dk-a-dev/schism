#!/usr/bin/env python3
"""Print one or more receipts out of a generated fixture file: `python3 show.py out/sroie-corpus.txt 012 021`."""
import sys

path, ids = sys.argv[1], set(sys.argv[2:])
on = False
for line in open(path, encoding="utf-8"):
    if line.startswith(">>> "):
        on = line[4:].split("\t")[0] in ids
        if on:
            print("=" * 70)
    if on:
        print(line.rstrip())
