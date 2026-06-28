#!/bin/bash
echo "Modified: $(git diff --name-only | wc -l)" && echo "Staged: $(git diff --cached --name-only | wc -l)" && echo "Untracked: $(git ls-files --others --exclude-standard | wc -l)"
