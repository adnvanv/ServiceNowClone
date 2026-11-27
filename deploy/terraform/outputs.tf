output "app_url" {
  description = "Public URL of the app (the Traefik load balancer). DNS can take a minute or two to resolve after apply."
  value       = "http://${try(data.kubernetes_service_v1.traefik.status[0].load_balancer[0].ingress[0].hostname, "pending")}"
}

output "kubeconfig_command" {
  description = "Point kubectl at the new cluster."
  value       = "aws eks update-kubeconfig --region ${var.region} --name ${module.eks.cluster_name}"
}

output "cluster_name" {
  value = module.eks.cluster_name
}

output "db_endpoint" {
  description = "RDS endpoint (private; reachable only from the cluster)."
  value       = aws_db_instance.platform.address
}
