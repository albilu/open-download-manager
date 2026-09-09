# Simple Makefile for Open Download Manager Docker Development

.PHONY: help build dev test test-integration test-perf compile run debug package clean

.DEFAULT_GOAL := help

help: ## Show this help message
	@echo "Open Download Manager - Simple Docker Development"
	@echo ""
	@echo "Available targets:"
	@echo "  build     Build Docker image"
	@echo "  dev       Start development container"
	@echo "  test      Run tests excluding integration/E2E and performance suites"
	@echo "  test-integration  Run tests including integration/E2E suites"
	@echo "  test-perf Run performance benchmarks only"
	@echo "  compile   Build application"
	@echo "  run       Run application in Docker with Xvfb"
	@echo "  debug     Run application in debug mode (port 5005)"
	@echo "  package   Create distribution packages"
	@echo "  clean     Clean up Docker resources"
	@echo ""
	@echo "Examples:"
	@echo "  make build    # Build the Docker image"
	@echo "  make dev      # Start development environment"
	@echo "  make test     # Run the default test suite"
	@echo "  make test-perf # Run performance benchmarks"
	@echo "  make run      # Run application with GUI support"
	@echo "  make debug    # Run application in debug mode"
	@echo "  make package  # Create .deb, .rpm packages"

build: ## Build Docker image
	./docker-build.sh build

dev: ## Start development container
	./docker-build.sh dev

test: ## Run the default suite without integration/E2E or performance tests
	./docker-build.sh test

test-integration: ## Run tests including integration/E2E suites (-Pintegration)
	./docker-build.sh test-integration

test-perf: ## Run performance benchmarks only (-Pperf)
	./docker-build.sh test-perf

compile: ## Build application
	./docker-build.sh compile

run: ## Run application in Docker with Xvfb
	./docker-build.sh run

debug: ## Run application in debug mode (port 5005)
	./docker-build.sh debug

package: ## Create distribution packages
	./docker-build.sh package

clean: ## Clean up Docker resources
	./docker-build.sh clean
