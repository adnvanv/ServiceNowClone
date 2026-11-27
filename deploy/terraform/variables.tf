variable "region" {
  description = "AWS region to deploy into."
  type        = string
  default     = "us-east-1"
}

variable "name" {
  description = "Name prefix for the cluster, VPC and database."
  type        = string
  default     = "glidelike"
}

variable "kubernetes_version" {
  description = "EKS Kubernetes version."
  type        = string
  default     = "1.34"
}

variable "node_instance_type" {
  description = "Instance type for the EKS managed node group. t3.medium (2 vCPU / 4 GiB) fits 3 backends + frontend + add-ons on two nodes."
  type        = string
  default     = "t3.medium"
}

variable "node_count" {
  description = "Desired number of worker nodes (min 1, max node_count + 2 for the HPA to grow into)."
  type        = number
  default     = 2
}

variable "db_instance_class" {
  description = "RDS instance class. db.t4g.micro is the cheapest Graviton class."
  type        = string
  default     = "db.t4g.micro"
}

variable "image_tag" {
  description = "Tag of the backend and frontend images in GHCR (e.g. sha-<commit> from CI, or latest)."
  type        = string
  default     = "latest"
}

variable "image_registry" {
  description = "Registry/owner prefix the images are pushed to by CI."
  type        = string
  default     = "ghcr.io/adnvanv"
}

variable "ghcr_pull_token" {
  description = "Optional GitHub token with read:packages, used as an image pull secret. Leave empty if the GHCR packages are public."
  type        = string
  default     = ""
  sensitive   = true
}

variable "budget_alert_email" {
  description = "If set, creates a monthly AWS Budget that emails this address at 50% / 100% of budget_limit_usd. Set it before your first apply."
  type        = string
  default     = ""
}

variable "budget_limit_usd" {
  description = "Monthly budget for the billing alert, in USD."
  type        = number
  default     = 20
}
