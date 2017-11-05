package com.lightbend.lagom.discovery.consul

import java.net.{InetAddress, URI}
import java.util.concurrent.ThreadLocalRandom
import java.util.function.{Function => JFunction}
import javax.inject.Inject

import com.ecwid.consul.v1.{ConsulClient, QueryParams}
import com.ecwid.consul.v1.agent.model.NewService
import com.ecwid.consul.v1.catalog.model.CatalogService
import com.lightbend.lagom.scaladsl.api.{Descriptor, ServiceLocator}

import scala.collection.JavaConverters._
import scala.collection.concurrent.{Map, TrieMap}
import scala.concurrent.{ExecutionContext, Future}


class ConsulServiceLocator @Inject()(client: ConsulClient, config: ConsulConfig)(implicit ec: ExecutionContext) extends ServiceLocator {

  private val roundRobinIndexFor: Map[String, Int] = TrieMap.empty[String, Int]

  val service = new NewService
  service.setId(config.serviceId)
  service.setName(config.serviceName)
  service.setPort(config.servicePort)
  service.setAddress(config.serviceAddress)
  new ConsulClient(config.agentHostname).agentServiceRegister(service)

  override def locate(name: String, serviceCall: Descriptor.Call[_, _]): Future[Option[URI]] = locateAsScala(name)

  override def doWithService[T](name: String, serviceCall: Descriptor.Call[_, _])(block: URI => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
    locateAsScala(name).flatMap { uriOpt =>
      uriOpt.fold(Future.successful(Option.empty[T])) { uri =>
        block.apply(uri).map(Some(_))
      }
    }

  private def locateAsScala(name: String): Future[Option[URI]] = Future {
    val instances = client.getCatalogService(name, QueryParams.DEFAULT).getValue.asScala.toList
    instances.size match {
      case 0 => None
      case 1 => toURIs(instances).headOption
      case _ =>
        config.routingPolicy match {
          case First => Some(pickFirstInstance(instances))
          case Random => Some(pickRandomInstance(instances))
          case RoundRobin => Some(pickRoundRobinInstance(name, instances))
        }
    }
  }

  private implicit object DefaultOrdering extends Ordering[URI] {
    override def compare(x: URI, y: URI): Int = x.compareTo(y)
  }

  private[consul] def pickFirstInstance(services: List[CatalogService]): URI = {
    if (services.isEmpty) throw new IllegalStateException("List of services should not be empty")
    toURIs(services).sorted.head
  }

  private[consul] def pickRandomInstance(services: List[CatalogService]): URI = {
    if (services.isEmpty) throw new IllegalStateException("List of services should not be empty")
    toURIs(services).sorted.apply(ThreadLocalRandom.current.nextInt(services.size - 1))
  }

  private[consul] def pickRoundRobinInstance(name: String, services: List[CatalogService]): URI = {
    if (services.isEmpty) throw new IllegalStateException("List of services should not be empty")
    roundRobinIndexFor.putIfAbsent(name, 0)
    val sortedServices = toURIs(services).sorted
    val currentIndex = roundRobinIndexFor(name)
    val nextIndex =
      if (sortedServices.size > currentIndex + 1) currentIndex + 1
      else 0
    roundRobinIndexFor.replace(name, nextIndex)
    sortedServices.apply(currentIndex)
  }

  private def toURIs(services: List[CatalogService]): List[URI] =
    services.map { service =>
      val address = service.getServiceAddress
      val serviceAddress =
        if (address.trim.isEmpty || address == "localhost") InetAddress.getLoopbackAddress.getHostAddress
        else address
      new URI(s"${config.scheme}://$serviceAddress:${service.getServicePort}")
    }

}
