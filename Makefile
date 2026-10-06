.PHONY: test kondo build clean check
test:
	clojure -M:test
kondo:
	clojure -M:kondo --lint src test
build:
	clojure -T:build jar
clean:
	clojure -T:build clean
check: test kondo build
	@echo "Phase 0 check ok"
