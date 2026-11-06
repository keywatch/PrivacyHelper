.PHONY: check syntax test build

check: syntax test

syntax:
	bash -n module/build.sh module/test-host.sh
	sh -n tools/capture_binder.sh
	python3 -c 'import ast, pathlib; [ast.parse(p.read_text(), filename=str(p)) for p in pathlib.Path("tools").glob("*.py")]; print("Python syntax: passed")'
	node --check tools/native_agent.js
	git diff --check

test:
	bash module/test-host.sh

build:
	bash module/build.sh
