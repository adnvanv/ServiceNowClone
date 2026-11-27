# Glidelike on Kubernetes, locally, in one command:
#
#   make up        kind cluster + platform add-ons + build/load images + helm install
#   make smoke     run the smoke test against the running cluster
#   make chaos     delete a backend pod mid-traffic and check nothing failed
#   make down      delete the cluster
#
# Needs: docker, kind, kubectl, helm (and bash; on Windows run from Git Bash).

SHELL := bash
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := help

CLUSTER    ?= glidelike
NAMESPACE  ?= glidelike
RELEASE    ?= glidelike
TAG        ?= dev
BACKEND_IMAGE  ?= glidelike-backend:$(TAG)
FRONTEND_IMAGE ?= glidelike-frontend:$(TAG)
BASE_URL   ?= http://localhost:8000
CHART      := deploy/helm/glidelike
KUBECTX    := kind-$(CLUSTER)
# Pinned add-on chart versions (also used by Terraform), so a new upstream
# release cannot silently break `make up` or CI.
CNPG_CHART_VERSION           ?= 0.29.2
TRAEFIK_CHART_VERSION        ?= 43.0.0
METRICS_SERVER_CHART_VERSION ?= 3.14.0
KUBECTL    := kubectl --context $(KUBECTX)
HELM       := helm --kube-context $(KUBECTX)

.PHONY: help up cluster addons images load install smoke chaos status down

help: ## Show targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-10s %s\n", $$1, $$2}'

up: cluster addons images load install ## Create the cluster and deploy everything
	@echo
	@echo "Glidelike is up: $(BASE_URL)  (log in as admin/admin)"

cluster: ## Create the kind cluster (no-op if it exists)
	@if kind get clusters 2>/dev/null | grep -qx '$(CLUSTER)'; then \
	  echo "kind cluster '$(CLUSTER)' already exists"; \
	else \
	  kind create cluster --name $(CLUSTER) --config deploy/kind/kind-config.yaml --wait 120s; \
	fi

addons: ## Install Traefik (ingress), metrics-server (HPA) and the CloudNativePG operator
	helm repo add traefik https://traefik.github.io/charts >/dev/null 2>&1 || true
	helm repo add cnpg https://cloudnative-pg.github.io/charts >/dev/null 2>&1 || true
	helm repo add metrics-server https://kubernetes-sigs.github.io/metrics-server/ >/dev/null 2>&1 || true
	helm repo update >/dev/null
	$(HELM) upgrade --install cnpg cnpg/cloudnative-pg --version $(CNPG_CHART_VERSION) \
	  --namespace cnpg-system --create-namespace --wait --timeout 5m
	$(HELM) upgrade --install traefik traefik/traefik --version $(TRAEFIK_CHART_VERSION) \
	  --namespace traefik --create-namespace \
	  --set service.spec.type=NodePort \
	  --set ports.web.nodePort=30080 \
	  --wait --timeout 5m
	# kind's kubelets use self-signed certs, hence --kubelet-insecure-tls (local only).
	$(HELM) upgrade --install metrics-server metrics-server/metrics-server --version $(METRICS_SERVER_CHART_VERSION) \
	  --namespace kube-system \
	  --set 'args={--kubelet-insecure-tls}' \
	  --wait --timeout 5m

images: ## Build the backend and frontend images
	docker build -t $(BACKEND_IMAGE) backend
	docker build -t $(FRONTEND_IMAGE) frontend

load: ## Load the images into the kind node (no registry needed)
	kind load docker-image --name $(CLUSTER) $(BACKEND_IMAGE) $(FRONTEND_IMAGE)

install: ## helm upgrade --install the chart and wait for the rollout
	@existed=0; $(HELM) -n $(NAMESPACE) status $(RELEASE) >/dev/null 2>&1 && existed=1; \
	$(HELM) upgrade --install $(RELEASE) $(CHART) \
	  --namespace $(NAMESPACE) --create-namespace \
	  -f $(CHART)/values-kind.yaml \
	  --set backend.image.tag=$(TAG) --set frontend.image.tag=$(TAG) \
	  --wait --timeout 10m; \
	if [ $$existed = 1 ]; then \
	  echo "Release existed: restarting pods so they pick up the freshly loaded :$(TAG) images"; \
	  $(KUBECTL) -n $(NAMESPACE) rollout restart deploy/$(RELEASE)-backend deploy/$(RELEASE)-frontend; \
	fi
	$(KUBECTL) -n $(NAMESPACE) rollout status deploy/$(RELEASE)-backend --timeout 5m
	$(KUBECTL) -n $(NAMESPACE) rollout status deploy/$(RELEASE)-frontend --timeout 2m

smoke: ## Log in, create a table, insert and query a record
	BASE_URL=$(BASE_URL) ./scripts/smoke-test.sh

chaos: ## Kill a backend pod under load and verify zero failed requests
	BASE_URL=$(BASE_URL) NAMESPACE=$(NAMESPACE) KUBE_CONTEXT=$(KUBECTX) \
	  RELEASE=$(RELEASE) ./scripts/chaos-test.sh

status: ## Show pods, HPA and the database cluster
	$(KUBECTL) -n $(NAMESPACE) get pods,svc,ingress,hpa,pdb -o wide
	$(KUBECTL) -n $(NAMESPACE) get clusters.postgresql.cnpg.io

down: ## Delete the kind cluster
	kind delete cluster --name $(CLUSTER)
