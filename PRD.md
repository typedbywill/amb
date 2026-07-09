# Android Mic Bridge

**Android Mic Bridge** é um projeto Open Source inspirado na simplicidade e eficiência do SCRCPY, com foco exclusivo na transmissão de áudio do **microfone** do Android para computadores.

O objetivo é fornecer uma solução gratuita, de baixa latência e multiplataforma para utilizar um dispositivo Android como microfone em Windows, Linux e macOS, sem depender de serviços em nuvem ou aplicativos proprietários.

## Objetivos

* Transmitir o áudio do microfone do Android em tempo real.
* Priorizar baixa latência e alta qualidade de áudio.
* Comunicação via USB (ADB) e Wi-Fi.
* Configuração mínima, semelhante ao SCRCPY.
* Não exigir root no dispositivo Android.
* Projeto totalmente Open Source.

## Funcionalidades

* Captura do microfone utilizando a API AudioRecord do Android.
* Codificação em Opus para reduzir largura de banda mantendo alta qualidade.
* Transporte via ADB Reverse (USB) ou TCP/UDP (Wi-Fi).
* Cliente desktop para Windows, Linux e macOS.
* Suporte a múltiplas taxas de amostragem (16 kHz, 24 kHz, 48 kHz).
* Controle de ganho, mute e monitoramento da latência.
* Indicador de qualidade da conexão.
* Reconexão automática.

## Arquitetura

```
Android
 ├── AudioRecord
 ├── Encoder (Opus)
 └── Transport Layer
         │
         ├── ADB Reverse (USB)
         └── TCP/UDP (Wi-Fi)
                 │
Desktop Client
 ├── Decoder (Opus)
 ├── Audio Output
 └── Virtual Microphone (opcional)
```

## Componentes

### Aplicativo Android

Responsável apenas por:

* Capturar o áudio do microfone.
* Codificar utilizando Opus.
* Transmitir continuamente os pacotes para o cliente desktop.

### Cliente Desktop

Responsável por:

* Descobrir dispositivos automaticamente.
* Receber e decodificar o fluxo de áudio.
* Reproduzir o áudio localmente.
* Opcionalmente disponibilizar o áudio como um dispositivo de entrada virtual (microfone) para aplicações como Discord, OBS, Teams, Zoom e jogos.

## Prioridades do projeto

1. Baixa latência.
2. Baixo consumo de bateria.
3. Simplicidade de uso.
4. Código limpo e modular.
5. Alta estabilidade.
6. Compatibilidade multiplataforma.

## Tecnologias sugeridas

### Android

* Kotlin
* AudioRecord
* Opus Codec
* Coroutines

### Desktop

* Rust ou C++
* libopus
* SDL2, PortAudio ou miniaudio
* Qt (opcional para interface gráfica)

## Linha de comando

Inspirado no SCRCPY:

```bash
miccpy
```

```bash
miccpy --usb
```

```bash
miccpy --wifi
```

```bash
miccpy --bitrate 64k
```

```bash
miccpy --sample-rate 48000
```

```bash
miccpy --monitor
```

## Diferenciais

* Sem necessidade de login.
* Sem servidores intermediários.
* Comunicação totalmente local.
* Código auditável.
* Licença permissiva (MIT ou Apache 2.0).
* API reutilizável para integração em outros projetos.
* Arquitetura modular para facilitar contribuições da comunidade.

## Possíveis evoluções

* Compressão adaptativa conforme a qualidade da rede.
* Cancelamento de eco.
* Redução de ruído.
* AGC (Automatic Gain Control).
* Descoberta automática via mDNS.
* Interface gráfica multiplataforma.
* Biblioteca (SDK) para integração em aplicações de terceiros.
* Modo "push-to-talk".
* Transmissão estéreo quando suportada pelo hardware.
* Criação automática de dispositivo virtual de microfone em cada sistema operacional.

## Visão

O projeto busca ser para o áudio do microfone o que o SCRCPY se tornou para o espelhamento de tela: uma ferramenta Open Source, rápida, confiável, de baixa latência e extremamente simples de usar, tornando o Android um microfone de alta qualidade para qualquer computador.
