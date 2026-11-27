# Cluster add-ons and the application itself — the exact same Helm chart that
# `make up` installs on kind and CI installs on every push. Only the values
# differ: images come from GHCR and the database is RDS.

resource "helm_release" "metrics_server" {
  name       = "metrics-server"
  repository = "https://kubernetes-sigs.github.io/metrics-server/"
  chart      = "metrics-server"
  version    = "3.14.0"
  namespace  = "kube-system"

  depends_on = [module.eks]
}

# Traefik is the Ingress controller; its LoadBalancer Service gets an AWS
# load balancer in the public subnets.
resource "helm_release" "traefik" {
  name             = "traefik"
  repository       = "https://traefik.github.io/charts"
  chart            = "traefik"
  version          = "43.0.0"
  namespace        = "traefik"
  create_namespace = true

  set = [
    {
      name  = "service.spec.type"
      value = "LoadBalancer"
    },
  ]

  depends_on = [module.eks]
}

resource "kubernetes_namespace_v1" "app" {
  metadata {
    name = "glidelike"
  }

  depends_on = [module.eks]
}

resource "kubernetes_secret_v1" "ghcr" {
  count = var.ghcr_pull_token != "" ? 1 : 0

  metadata {
    name      = "ghcr"
    namespace = kubernetes_namespace_v1.app.metadata[0].name
  }

  type = "kubernetes.io/dockerconfigjson"

  data = {
    ".dockerconfigjson" = jsonencode({
      auths = {
        "ghcr.io" = {
          auth = base64encode("token:${var.ghcr_pull_token}")
        }
      }
    })
  }
}

resource "helm_release" "glidelike" {
  name      = "glidelike"
  chart     = "${path.module}/../helm/glidelike"
  namespace = kubernetes_namespace_v1.app.metadata[0].name

  wait    = true
  timeout = 900

  set = concat([
    { name = "backend.image.repository", value = "${var.image_registry}/servicenowclone-backend" },
    { name = "backend.image.tag", value = var.image_tag },
    { name = "frontend.image.repository", value = "${var.image_registry}/servicenowclone-frontend" },
    { name = "frontend.image.tag", value = var.image_tag },
    { name = "postgres.mode", value = "external" },
    { name = "postgres.external.host", value = aws_db_instance.platform.address },
    { name = "postgres.external.port", value = tostring(aws_db_instance.platform.port) },
    { name = "postgres.external.sslMode", value = "require" },
    ], var.ghcr_pull_token != "" ? [
    { name = "imagePullSecrets[0].name", value = "ghcr" },
  ] : [])

  set_sensitive = [
    { name = "postgres.password", value = random_password.db.result },
  ]

  depends_on = [
    helm_release.traefik,
    helm_release.metrics_server,
    aws_vpc_security_group_ingress_rule.db_from_nodes,
    kubernetes_secret_v1.ghcr,
  ]
}

data "kubernetes_service_v1" "traefik" {
  metadata {
    name      = "traefik"
    namespace = "traefik"
  }

  depends_on = [helm_release.traefik]
}
