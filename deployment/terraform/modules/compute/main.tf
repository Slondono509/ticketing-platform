# ECS Fargate service running the application (API + SQS consumers) in private subnets behind an internal ALB.
# The ALB is only reachable from the API Gateway VPC link; tasks have no public IP.

data "aws_region" "current" {}

locals {
  container_name = "app"
  container_port = 8080
}

# ---------------------------------------------------------------- Registry
resource "aws_ecr_repository" "this" {
  name                 = var.name
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = var.kms_key_arn
  }
}

resource "aws_ecr_lifecycle_policy" "this" {
  repository = aws_ecr_repository.this.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 30 images"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 30 }
      action       = { type = "expire" }
    }]
  })
}

# ---------------------------------------------------------------- Logs
resource "aws_cloudwatch_log_group" "app" {
  name              = "/ecs/${var.name}"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.kms_key_arn
}

# ---------------------------------------------------------------- IAM
data "aws_iam_policy_document" "ecs_tasks_trust" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# Execution role: used by the ECS agent to pull the image, write logs and read the secret.
resource "aws_iam_role" "execution" {
  name               = "${var.name}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_trust.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "execution_secrets" {
  name = "read-app-secrets"
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = "secretsmanager:GetSecretValue", Resource = var.admin_api_key_secret_arn },
      { Effect = "Allow", Action = "kms:Decrypt", Resource = var.kms_key_arn }
    ]
  })
}

# Task role: what the application itself may do. Least privilege, scoped to its own tables and queues.
resource "aws_iam_role" "task" {
  name               = "${var.name}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_trust.json
}

resource "aws_iam_role_policy" "task" {
  name = "ticketing-data-plane"
  role = aws_iam_role.task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "Events"
        Effect   = "Allow"
        Action   = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:Query"]
        Resource = [var.events_table_arn, "${var.events_table_arn}/index/*"]
      },
      {
        Sid      = "Orders"
        Effect   = "Allow"
        Action   = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:Query"]
        Resource = [var.orders_table_arn, "${var.orders_table_arn}/index/*"]
      },
      {
        Sid      = "AuditAppendOnly"
        Effect   = "Allow"
        Action   = ["dynamodb:PutItem"]
        Resource = [var.audit_table_arn]
      },
      {
        Sid      = "PublishOrderCommands"
        Effect   = "Allow"
        Action   = ["sqs:SendMessage"]
        Resource = [var.order_commands_queue_arn]
      },
      {
        Sid      = "ConsumeQueues"
        Effect   = "Allow"
        Action   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility", "sqs:GetQueueAttributes"]
        Resource = [var.order_commands_queue_arn, var.reservation_expiration_queue_arn]
      },
      {
        Sid      = "EncryptedData"
        Effect   = "Allow"
        Action   = ["kms:Decrypt", "kms:GenerateDataKey"]
        Resource = [var.kms_key_arn]
      }
    ]
  })
}

# ---------------------------------------------------------------- Networking
resource "aws_security_group" "alb" {
  name        = "${var.name}-alb"
  description = "Internal ALB, only reachable from the API Gateway VPC link"
  vpc_id      = var.vpc_id

  ingress {
    description     = "HTTP from the VPC link"
    from_port       = 80
    to_port         = 80
    protocol        = "tcp"
    security_groups = [var.vpc_link_security_group_id]
  }

  egress {
    description = "To the tasks"
    from_port   = local.container_port
    to_port     = local.container_port
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }
}

resource "aws_security_group" "tasks" {
  name        = "${var.name}-tasks"
  description = "ECS tasks, only reachable from the ALB"
  vpc_id      = var.vpc_id

  ingress {
    description     = "HTTP from the ALB"
    from_port       = local.container_port
    to_port         = local.container_port
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    description = "HTTPS to AWS endpoints and the payment provider"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_lb" "this" {
  name                       = var.name
  internal                   = true
  load_balancer_type         = "application"
  security_groups            = [aws_security_group.alb.id]
  subnets                    = var.private_subnet_ids
  drop_invalid_header_fields = true
  enable_deletion_protection = var.deletion_protection
}

resource "aws_lb_target_group" "this" {
  name                 = var.name
  port                 = local.container_port
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = var.vpc_id
  deregistration_delay = 30 # matches the graceful shutdown of the application

  health_check {
    path                = "/actuator/health/readiness"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 15
    timeout             = 5
    matcher             = "200"
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this.arn
  }
}

# ---------------------------------------------------------------- ECS
resource "aws_ecs_cluster" "this" {
  name = var.name

  setting {
    name  = "containerInsights"
    value = "enhanced"
  }
}

resource "aws_ecs_task_definition" "this" {
  family                   = var.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64" # Graviton: ~20% cheaper for the same performance
  }

  volume {
    name = "tmp"
  }

  container_definitions = jsonencode([{
    name                   = local.container_name
    image                  = "${aws_ecr_repository.this.repository_url}:${var.image_tag}"
    essential              = true
    readonlyRootFilesystem = true
    portMappings           = [{ containerPort = local.container_port, protocol = "tcp" }]
    mountPoints            = [{ sourceVolume = "tmp", containerPath = "/tmp" }]

    environment = [
      { name = "SPRING_PROFILES_ACTIVE", value = "aws" },
      { name = "AWS_REGION", value = data.aws_region.current.region },
      { name = "EVENTS_TABLE", value = var.events_table_name },
      { name = "ORDERS_TABLE", value = var.orders_table_name },
      { name = "AUDIT_TABLE", value = var.audit_table_name },
      { name = "ORDER_COMMANDS_QUEUE_URL", value = var.order_commands_queue_url },
      { name = "RESERVATION_EXPIRATION_QUEUE_URL", value = var.reservation_expiration_queue_url },
      { name = "JAVA_OPTS", value = "-XX:+UseContainerSupport -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" }
    ]

    secrets = [
      { name = "ADMIN_API_KEY", valueFrom = var.admin_api_key_secret_arn }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.app.name
        awslogs-region        = data.aws_region.current.region
        awslogs-stream-prefix = "app"
      }
    }
  }])
}

resource "aws_ecs_service" "this" {
  name                              = var.name
  cluster                           = aws_ecs_cluster.this.id
  task_definition                   = aws_ecs_task_definition.this.arn
  desired_count                     = var.min_tasks
  launch_type                       = "FARGATE"
  health_check_grace_period_seconds = 60
  propagate_tags                    = "SERVICE"

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.this.arn
    container_name   = local.container_name
    container_port   = local.container_port
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  lifecycle {
    ignore_changes = [desired_count] # owned by the autoscaler
  }

  depends_on = [aws_lb_listener.http]
}

# ---------------------------------------------------------------- Autoscaling
resource "aws_appautoscaling_target" "this" {
  service_namespace  = "ecs"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.this.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = var.min_tasks
  max_capacity       = var.max_tasks
}

resource "aws_appautoscaling_policy" "cpu" {
  name               = "${var.name}-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this.service_namespace
  resource_id        = aws_appautoscaling_target.this.resource_id
  scalable_dimension = aws_appautoscaling_target.this.scalable_dimension

  target_tracking_scaling_policy_configuration {
    target_value       = 60
    scale_in_cooldown  = 120
    scale_out_cooldown = 30
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

# Scale out on order backlog: during an on-sale the queue grows before the CPU does.
resource "aws_appautoscaling_policy" "backlog" {
  name               = "${var.name}-order-backlog"
  policy_type        = "StepScaling"
  service_namespace  = aws_appautoscaling_target.this.service_namespace
  resource_id        = aws_appautoscaling_target.this.resource_id
  scalable_dimension = aws_appautoscaling_target.this.scalable_dimension

  step_scaling_policy_configuration {
    adjustment_type         = "ChangeInCapacity"
    cooldown                = 60
    metric_aggregation_type = "Maximum"

    step_adjustment {
      metric_interval_lower_bound = 0
      metric_interval_upper_bound = 5000
      scaling_adjustment          = 2
    }
    step_adjustment {
      metric_interval_lower_bound = 5000
      scaling_adjustment          = 4
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "backlog" {
  alarm_name          = "${var.name}-order-backlog-high"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = var.order_commands_queue_name }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  threshold           = var.backlog_scale_out_threshold
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = [aws_appautoscaling_policy.backlog.arn]
}
