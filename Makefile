.DEFAULT_GOAL := help
NAMESPACE := airticket

help: ## Show this help
	@grep -E '^[a-z-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

up: ## Start app + postgres locally
	docker compose up --build

down: ## Stop and remove the local stack and its data
	docker compose down -v

test: ## Run unit and slice tests with coverage
	mvn -B verify

image: ## Build the container image
	docker build -t airticket-booking-system:local .

deploy: ## Apply all manifests to the current kube context
	kubectl apply -k k8s/
	kubectl -n $(NAMESPACE) rollout status deployment/airticket-app

logs: ## Tail application logs from the cluster
	kubectl -n $(NAMESPACE) logs -l app.kubernetes.io/component=backend -f --tail=100

undeploy: ## Remove everything from the cluster
	kubectl delete -k k8s/ --ignore-not-found

.PHONY: help up down test image deploy logs undeploy
