data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, 2)
}

# Three subnet tiers across two AZs:
#   public   - the internet-facing load balancer and the NAT gateway
#   private  - EKS worker nodes (no public IPs)
#   database - RDS only; no route to the internet at all
module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 6.0"

  name = var.name
  cidr = "10.40.0.0/16"
  azs  = local.azs

  public_subnets   = ["10.40.0.0/24", "10.40.1.0/24"]
  private_subnets  = ["10.40.10.0/24", "10.40.11.0/24"]
  database_subnets = ["10.40.20.0/24", "10.40.21.0/24"]

  create_database_subnet_group = true

  # One NAT gateway instead of one per AZ: fine for a demo, and it is the
  # single biggest line item after the EKS control plane. Production would
  # use one per AZ so losing an AZ does not cut the other one off.
  enable_nat_gateway = true
  single_nat_gateway = true

  enable_dns_hostnames = true
  enable_dns_support   = true

  # Tags the AWS cloud provider uses to pick subnets for load balancers.
  public_subnet_tags = {
    "kubernetes.io/role/elb" = 1
  }
  private_subnet_tags = {
    "kubernetes.io/role/internal-elb" = 1
  }
}
