.DEFAULT_GOAL := help

.PHONY: help build test test-ios lint verify

help: ## List available targets.
	@awk 'BEGIN {FS = ":.*##"} /^[a-zA-Z_-]+:.*##/ {printf "%-12s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

build: ## Compile and package the CLI distribution
	./gradlew :maestro-cli:installDist

test: ## Run the complete Gradle test suite
	./gradlew test

test-ios: ## Run tests for the iOS modules
	./gradlew :maestro-ios-driver:check :maestro-ios:check

lint: ## Run Detekt
	./gradlew detekt

verify: build test lint ## Run the local build, complete test suite, and linter
