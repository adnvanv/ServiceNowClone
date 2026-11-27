# Managed Postgres instead of running Postgres in the cluster: backups,
# patching and storage are AWS's problem, and the database sits in isolated
# subnets reachable only from the EKS worker nodes' security group.

resource "random_password" "db" {
  length  = 32
  special = false # keeps the JDBC URL / env var handling trivially safe
}

resource "aws_security_group" "db" {
  name        = "${var.name}-db"
  description = "Postgres, reachable only from EKS worker nodes"
  vpc_id      = module.vpc.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "db_from_nodes" {
  security_group_id            = aws_security_group.db.id
  referenced_security_group_id = module.eks.node_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "Postgres from EKS nodes"
}

resource "aws_db_instance" "platform" {
  identifier     = "${var.name}-db"
  engine         = "postgres"
  engine_version = "16"
  instance_class = var.db_instance_class

  allocated_storage     = 20
  max_allocated_storage = 50
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name  = "platform"
  username = "platform"
  password = random_password.db.result

  db_subnet_group_name   = module.vpc.database_subnet_group_name
  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible    = false

  # Demo settings: single-AZ, no final snapshot, deletable — so
  # `terraform destroy` really does stop all charges. Production: multi_az,
  # deletion_protection, a final snapshot and a longer backup window.
  multi_az                = false
  backup_retention_period = 1
  skip_final_snapshot     = true
  deletion_protection     = false
  apply_immediately       = true
}
